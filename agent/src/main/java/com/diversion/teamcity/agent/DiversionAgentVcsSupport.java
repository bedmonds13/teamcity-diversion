package com.diversion.teamcity.agent;

import jetbrains.buildServer.agent.*;
import jetbrains.buildServer.agent.vcs.AgentCheckoutAbility;
import jetbrains.buildServer.agent.vcs.AgentVcsSupport;
import jetbrains.buildServer.agent.vcs.UpdateByCheckoutRules2;
import jetbrains.buildServer.agent.vcs.UpdatePolicy;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRoot;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Agent-side VCS support for Diversion
 * Handles checkout directly on the build agent using dv CLI
 * Assumes 'dv login' has been run on the agent before use.
 */
public class DiversionAgentVcsSupport extends AgentVcsSupport implements UpdateByCheckoutRules2 {

    @NotNull
    @Override
    public UpdatePolicy getUpdatePolicy() {
        return this;
    }

    @NotNull
    @Override
    public String getName() {
        return "diversion";  // Must match server-side getName()
    }

    @NotNull
    @Override
    public AgentCheckoutAbility canCheckout(
            @NotNull VcsRoot vcsRoot,
            @NotNull CheckoutRules checkoutRules,
            @NotNull AgentRunningBuild build) {

        // Check if 'dv' command is available on the agent
        if (!isDvCommandAvailable()) {
            return AgentCheckoutAbility.noVcsClientOnAgent(
                "Diversion (dv) command not found on agent. " +
                "Please install Diversion CLI and ensure it's in PATH.");
        }

        build.getBuildLogger().message("Diversion: Agent checkout is available");
        return AgentCheckoutAbility.canCheckout();
    }

    public void updateSources(
            @NotNull VcsRoot root,
            @NotNull CheckoutRules checkoutRules,
            @NotNull String toVersion,
            @NotNull File checkoutDirectory,
            @NotNull AgentRunningBuild build,
            boolean cleanCheckout) throws VcsException {

        BuildProgressLogger logger = build.getBuildLogger();
        logger.message("Diversion: Updating sources to " + toVersion);

        // Get VCS root properties
        String repoId = root.getProperty("repositoryId");
        String branch = root.getProperty("branchName");

        if (repoId == null || repoId.trim().isEmpty()) {
            throw new VcsException("Repository ID not configured in VCS root");
        }

        logger.message("Diversion: Repository ID: " + repoId);
        logger.message("Diversion: Branch: " + branch);
        logger.message("Diversion: Target version: " + toVersion);
        logger.message("Diversion: Checkout directory: " + checkoutDirectory.getAbsolutePath());
        logger.message("Diversion: Clean checkout: " + cleanCheckout);

        // The workspace marker is ".diversion". It was ".dv" here, which never matches, so
        // every non-clean build fell through to the re-clone path and failed: the running
        // agent holds ".diversion" open, the delete fails, and cloning into the surviving
        // directory exits 3.
        File dvDir = new File(checkoutDirectory, ".diversion");
        boolean workspaceExists = dvDir.exists() && dvDir.isDirectory();

        if (cleanCheckout) {
            // Clean checkout explicitly requested
            logger.message("Diversion: Clean checkout requested - removing existing workspace");

            // Clean directory if it exists
            if (checkoutDirectory.exists()) {
                // The registration lives in the agent's own config keyed by path, so it
                // outlives ".diversion" on disk: the agent keeps watching the directory and
                // the delete fails even when the directory is empty. Always try to release
                // it, and do not treat "nothing registered here" as an error.
                try {
                    logger.message("Diversion: Releasing any workspace registered at the checkout directory");
                    runDvCommand(checkoutDirectory, logger, "unregister", "-f");
                } catch (VcsException e) {
                    logger.message("Diversion: Nothing to unregister here, continuing");
                }

                logger.message("Diversion: Cleaning existing directory");
                deleteDirectory(checkoutDirectory, logger);

                // The watch handle is released asynchronously, so a delete immediately after
                // unregister can still lose the race. Give it a moment and retry.
                for (int attempt = 0; attempt < 3 && checkoutDirectory.exists(); attempt++) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    logger.message("Diversion: Directory still present, retrying delete");
                    deleteDirectory(checkoutDirectory, logger);
                }
            }

            // deleteDirectory only warns on failure. Cloning into a directory that survived
            // it fails with exit code 3 and, because TeamCity forces a clean checkout after a
            // failed checkout, wedges the build configuration. Stop here with a real reason.
            if (checkoutDirectory.exists()) {
                throw new VcsException(
                    "Failed to remove checkout directory " + checkoutDirectory.getAbsolutePath()
                    + " for clean checkout. Something still holds files in it open.");
            }

            // Ensure parent directory exists
            File parentDir = checkoutDirectory.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                logger.message("Diversion: Creating parent directory: " + parentDir.getAbsolutePath());
                parentDir.mkdirs();
            }

            // Clone repository
            logger.message("Diversion: Cloning repository to " + checkoutDirectory.getName());
            runDvCommand(parentDir, logger,
                "clone", repoId, checkoutDirectory.getName(), "--new-workspace");

        } else if (!workspaceExists) {
            // First time checkout - workspace doesn't exist yet
            logger.message("Diversion: First time checkout - initializing workspace");

            // Clean directory if it exists (TeamCity may have created it but it's not a valid workspace)
            if (checkoutDirectory.exists()) {
                logger.message("Diversion: Cleaning existing non-workspace directory");
                deleteDirectory(checkoutDirectory, logger);
            }

            // Ensure parent directory exists
            File parentDir = checkoutDirectory.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                logger.message("Diversion: Creating parent directory: " + parentDir.getAbsolutePath());
                parentDir.mkdirs();
            }

            // Clone repository
            logger.message("Diversion: Cloning repository to " + checkoutDirectory.getName());
            runDvCommand(parentDir, logger,
                "clone", repoId, checkoutDirectory.getName(), "--new-workspace");

        } else {
            // Incremental update - workspace already exists
            logger.message("Diversion: Incremental update - reusing existing workspace");
        }

        // Checkout specific commit
        logger.message("Diversion: Checking out version " + toVersion);
        runDvCommand(checkoutDirectory, logger,
            "checkout", toVersion, "--discard-changes");

        logger.message("Diversion: Checkout completed successfully");
    }

    /**
     * Check if dv command is available
     */
    private boolean isDvCommandAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("dv", "--help");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Consume output
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // Just consume the output
                }
            }

            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Run a dv command
     */
    private void runDvCommand(File workingDir, BuildProgressLogger logger,
                              String... args) throws VcsException {
        try {
            // Build command
            List<String> command = new ArrayList<>();
            command.add("dv");
            command.addAll(Arrays.asList(args));

            logger.message("Diversion: Running: " + String.join(" ", command));
            if (workingDir != null) {
                logger.message("Diversion: Working directory: " + workingDir.getAbsolutePath());
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null) {
                pb.directory(workingDir);
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Stream output to build log
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.message("  " + line);
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new VcsException("Diversion command failed with exit code " + exitCode +
                    "\nOutput: " + output.toString());
            }

        } catch (Exception e) {
            if (e instanceof VcsException) {
                throw (VcsException) e;
            }
            throw new VcsException("Failed to run Diversion command", e);
        }
    }

    /**
     * Recursively delete a directory
     */
    private void deleteDirectory(File directory, BuildProgressLogger logger) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file, logger);
                    } else {
                        if (!file.delete()) {
                            logger.warning("Failed to delete file: " + file.getAbsolutePath());
                        }
                    }
                }
            }
            if (!directory.delete()) {
                logger.warning("Failed to delete directory: " + directory.getAbsolutePath());
            }
        }
    }
}
