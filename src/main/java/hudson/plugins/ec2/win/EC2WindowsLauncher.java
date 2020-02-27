package hudson.plugins.ec2.win;

import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EC2ComputerLauncher;
import hudson.plugins.ec2.EC2HostAddressProvider;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.plugins.ec2.win.winrm.WindowsProcess;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
import hudson.slaves.ComputerLauncher;
import hudson.Util;
import hudson.os.WindowsUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.GetPasswordDataRequest;
import com.amazonaws.services.ec2.model.GetPasswordDataResult;

public class EC2WindowsLauncher extends EC2ComputerLauncher {
    private static final String AGENT_JAR = "remoting.jar";

    final long sleepBetweenAttempts = TimeUnit.SECONDS.toMillis(10);
    private static final Logger LOGGER = Logger.getLogger(EC2WindowsLauncher.class.getName());

    @Override
    protected void launchScript(EC2Computer computer, TaskListener listener) throws IOException,
            AmazonClientException, InterruptedException {
        final PrintStream logger = listener.getLogger();

        EC2AbstractSlave node = computer.getNode();
        if (node == null) {
            logger.println("Unable to fetch node information");
            LOGGER.log(Level.FINE, "Unable to fetch node information");
            return;
        }
        final SlaveTemplate template = computer.getSlaveTemplate();
        if (template == null) {
            throw new IOException("Could not find corresponding slave template for " + computer.getDisplayName());
        }

        final WinConnection connection = connectToWinRM(computer, node, template, logger);

        try {
            String initScript = node.initScript;
            String tmpDir = (node.tmpDir != null && !node.tmpDir.equals("") ? WindowsUtil.quoteArgument(Util.ensureEndsWith(node.tmpDir,"\\"))
                    : "C:\\Windows\\Temp\\");

            logger.println("Creating tmp directory if it does not exist");
            LOGGER.log(Level.FINE, "Creating tmp directory if it does not exist");
            WindowsProcess mkdirProcess = connection.execute("if not exist " + tmpDir + " mkdir " + tmpDir);
            int exitCode = mkdirProcess.waitFor();
            if (exitCode != 0) {
                logger.println("Creating tmpdir failed=" + exitCode);
                LOGGER.log(Level.FINE, "Creating tmpdir failed=" + exitCode);
                return;
            }

            if (initScript != null && initScript.trim().length() > 0 && !connection.exists(tmpDir + ".jenkins-init")) {
                logger.println("Executing init script");
                LOGGER.log(Level.FINE, "Executing init script");
                try(OutputStream init = connection.putFile(tmpDir + "init.bat")) {
                    init.write(initScript.getBytes("utf-8"));
                }

                WindowsProcess initProcess = connection.execute("cmd /c " + tmpDir + "init.bat");
                IOUtils.copy(initProcess.getStdout(), logger);

                int exitStatus = initProcess.waitFor();
                if (exitStatus != 0) {
                    logger.println("init script failed: exit code=" + exitStatus);
                    LOGGER.log(Level.FINE, "init script failed: exit code=" + exitStatus);
                    return;
                }

                try(OutputStream initGuard = connection.putFile(tmpDir + ".jenkins-init")) {
                    initGuard.write("init ran".getBytes(StandardCharsets.UTF_8));
                }
                logger.println("init script ran successfully");
                LOGGER.log(Level.FINE, "init script ran successfully");
            }

            try(OutputStream agentJar = connection.putFile(tmpDir + AGENT_JAR)) {
                agentJar.write(Jenkins.get().getJnlpJars(AGENT_JAR).readFully());
            }

            logger.println("remoting.jar sent remotely. Bootstrapping it");
            LOGGER.log(Level.FINE, "remoting.jar sent remotely. Bootstrapping it");

            final String jvmopts = node.jvmopts;
            final String remoteFS = WindowsUtil.quoteArgument(node.getRemoteFS());
            final String workDir = Util.fixEmptyAndTrim(remoteFS) != null ? remoteFS : tmpDir;
            final String launchString = "java " + (jvmopts != null ? jvmopts : "") + " -jar " + tmpDir + AGENT_JAR + " -workDir " + workDir;
            logger.println("Launching via WinRM:" + launchString);
            LOGGER.log(Level.FINE, "Launching via WinRM:" + launchString);
            final WindowsProcess process = connection.execute(launchString, 86400);
            computer.setChannel(process.getStdout(), process.getStdin(), logger, new Listener() {
                @Override
                public void onClosed(Channel channel, IOException cause) {
                    process.destroy();
                    connection.close();
                }
            });
        } catch (Throwable ioe) {
            logger.println("Ouch:");
            LOGGER.log(Level.FINE, "Ouch:" + ioe.fillInStackTrace());
            ioe.printStackTrace(logger);
        } finally {
            connection.close();
        }
    }

    @Nonnull
    private WinConnection connectToWinRM(EC2Computer computer, EC2AbstractSlave node, SlaveTemplate template, PrintStream logger) throws AmazonClientException,
            InterruptedException {
        final long minTimeout = 3000;
        long timeout = node.getLaunchTimeoutInMillis(); // timeout is less than 0 when jenkins is booting up.
        if (timeout < minTimeout) {
            timeout = minTimeout;
        }
        final long startTime = System.currentTimeMillis();

        logger.println(node.getDisplayName() + " booted at " + node.getCreatedTime());
        LOGGER.log(Level.FINE, node.getDisplayName() + " booted at " + node.getCreatedTime());
        boolean alreadyBooted = (startTime - node.getCreatedTime()) > TimeUnit.MINUTES.toMillis(3);
        WinConnection connection = null;
        while (true) {
            try {
                long waitTime = System.currentTimeMillis() - startTime;
                if (waitTime > timeout) {
                    throw new AmazonClientException("Timed out after " + (waitTime / 1000)
                            + " seconds of waiting for winrm to be connected");
                }

                if (connection == null) {
                    Instance instance = computer.updateInstanceDescription();
                    String host = EC2HostAddressProvider.windows(instance, template.connectionStrategy);

                    if ("0.0.0.0".equals(host)) {
                        logger.println("Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                        LOGGER.log(Level.FINE, "Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                        throw new IOException("goto sleep");
                    }

                    if (!node.isSpecifyPassword()) {
                        GetPasswordDataResult result;
                        try {
                            result = node.getCloud().connect().getPasswordData(new GetPasswordDataRequest(instance.getInstanceId()));
                        } catch (Exception e) {
                            logger.println("Unexpected Exception: " + e.toString());
                            LOGGER.log(Level.FINE, "Unexpected Exception: " + e.toString());
                            Thread.sleep(sleepBetweenAttempts);
                            continue;
                        }
                        String passwordData = result.getPasswordData();
                        if (passwordData == null || passwordData.isEmpty()) {
                            logger.println("Waiting for password to be available. Sleeping 10s.");
                            LOGGER.log(Level.FINE, "Waiting for password to be available. Sleeping 10s.");
                            Thread.sleep(sleepBetweenAttempts);
                            continue;
                        }
                        String password = node.getCloud().getPrivateKey().decryptWindowsPassword(passwordData);
                        if (!node.getRemoteAdmin().equals("Administrator")) {
                            logger.println("WARNING: For password retrieval remote admin must be Administrator, ignoring user provided value");
                            LOGGER.log(Level.FINE, "WARNING: For password retrieval remote admin must be Administrator, ignoring user provided value");
                        }
                        logger.println("Connecting to " + "(" + host + ") with WinRM as Administrator");
                        LOGGER.log(Level.FINE, "Connecting to " + "(" + host + ") with WinRM as Administrator");
                        connection = new WinConnection(host, "Administrator", password);
                    } else { //password Specified
                        logger.println("Connecting to " + "(" + host + ") with WinRM as " + node.getRemoteAdmin());
                        LOGGER.log(Level.FINE, "Connecting to " + "(" + host + ") with WinRM as " + node.getRemoteAdmin());
                        connection = new WinConnection(host, node.getRemoteAdmin(), node.getAdminPassword().getPlainText());
                    }
                    connection.setUseHTTPS(node.isUseHTTPS());
                }

                if (!connection.ping()) {
                    logger.println("Waiting for WinRM to come up. Sleeping 10s.");
                    LOGGER.log(Level.FINE, "Waiting for WinRM to come up. Sleeping 10s.");
                    Thread.sleep(sleepBetweenAttempts);
                    continue;
                }

                if (!alreadyBooted || node.stopOnTerminate) {
                    logger.println("WinRM service responded. Waiting for WinRM service to stabilize on "
                            + node.getDisplayName());
                    LOGGER.log(Level.FINE, "WinRM service responded. Waiting for WinRM service to stabilize on "
                            + node.getDisplayName());
                    Thread.sleep(node.getBootDelay());
                    alreadyBooted = true;
                    logger.println("WinRM should now be ok on " + node.getDisplayName());
                    LOGGER.log(Level.FINE, "WinRM should now be ok on " + node.getDisplayName());
                    if (!connection.ping()) {
                        logger.println("WinRM not yet up. Sleeping 10s.");
                        LOGGER.log(Level.FINE, "WinRM not yet up. Sleeping 10s.");
                        Thread.sleep(sleepBetweenAttempts);
                        continue;
                    }
                }

                logger.println("Connected with WinRM.");
                LOGGER.log(Level.FINE, "Connected with WinRM.");
                return connection; // successfully connected
            } catch (IOException e) {
                logger.println("Waiting for WinRM to come up. Sleeping 10s.");
                LOGGER.log(Level.FINE, "Waiting for WinRM to come up. Sleeping 10s.");
                Thread.sleep(sleepBetweenAttempts);
            }
        }
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
