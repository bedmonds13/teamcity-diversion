package com.diversion.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.buildTriggers.vcs.AbstractVcsPropertiesProcessor;
import jetbrains.buildServer.serverSide.PropertiesProcessor;
import jetbrains.buildServer.serverSide.InvalidProperty;
import jetbrains.buildServer.vcs.*;
import jetbrains.buildServer.vcs.patches.PatchBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;

/**
 * Main VCS support implementation for Diversion version control system.
 *
 * <p>This plugin supports both server-side and agent-side checkout modes:
 * <ul>
 *   <li><b>Server-side checkout</b>: Implements {@link BuildPatchByCheckoutRules} to build patches on the TeamCity server.
 *       Requires WORKING_DIRECTORY configuration but agents don't need Diversion CLI.</li>
 *   <li><b>Agent-side checkout</b>: Uses {@link com.diversion.teamcity.agent.DiversionAgentVcsSupport} on agents.
 *       Requires Diversion CLI installed and authenticated on all build agents.</li>
 * </ul>
 *
 * @see DiversionCommandExecutor for CLI command execution
 * @see DiversionCollectChangesPolicy for change detection logic
 */
public class DiversionVcsSupport extends ServerVcsSupport implements BuildPatchByCheckoutRules {

    private static final Logger LOG = Logger.getInstance(DiversionVcsSupport.class.getName());

    public DiversionVcsSupport() {
        // Default constructor
    }

    @NotNull
    @Override
    public String getName() {
        return "diversion";
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Diversion";
    }

    @NotNull
    @Override
    public String getVcsSettingsJspFilePath() {
        return "diversionSettings.jsp";
    }

    @NotNull
    @Override
    public String getCurrentVersion(@NotNull VcsRoot root) throws VcsException {
        String repoId = root.getProperty(DiversionSettings.REPOSITORY_ID);
        String branchName = root.getProperty(DiversionSettings.BRANCH_NAME, DiversionSettings.DEFAULT_BRANCH_NAME);
        LOG.info("Getting current version for repository " + repoId + " branch " + branchName);
        // Runs on TeamCity's polling interval, so it must not touch the workspace: a
        // checkout here discards an in-progress build's uncommitted output and moves the
        // workspace off the revision that build pinned.
        String version = createExecutor(root).getBranchHead(branchName);
        LOG.info("Current version: " + version);
        return version;
    }

    @NotNull
    public List<ModificationData> collectChanges(@NotNull VcsRoot root,
                                                   @NotNull String fromVersion,
                                                   @NotNull String currentVersion,
                                                   @NotNull CheckoutRules checkoutRules) throws VcsException {
        // Delegate to the collect changes policy to avoid code duplication
        RepositoryStateData fromState = RepositoryStateData.createSingleVersionState(fromVersion);
        RepositoryStateData toState = RepositoryStateData.createSingleVersionState(currentVersion);
        return getCollectChangesPolicy().collectChanges(root, fromState, toState, checkoutRules);
    }

    /**
     * Builds a patch for server-side checkout.
     *
     * This method enables server-side checkout by building patches that TeamCity can send to agents.
     * The implementation uses the Diversion CLI to checkout specific versions and read file contents.
     *
     * <p><b>Requirements:</b>
     * <ul>
     *   <li>WORKING_DIRECTORY must be configured in VCS root settings</li>
     *   <li>Diversion CLI must be installed and authenticated on TeamCity server</li>
     * </ul>
     *
     * <p><b>How it works:</b>
     * <ul>
     *   <li>Full checkout (fromVersion=null): Checks out toVersion and adds all files to patch</li>
     *   <li>Incremental checkout: Collects changes between versions, checks out each commit, and builds patch with modified files</li>
     * </ul>
     *
     * <p><b>Note:</b> Build agents do NOT need Diversion CLI when using server-side checkout.
     *
     * @param root The VCS root configuration
     * @param fromVersion The previous version (null for full checkout)
     * @param toVersion The target version to checkout
     * @param builder The TeamCity patch builder
     * @param checkoutRules Checkout rules to apply
     * @throws VcsException If checkout fails or working directory is not configured
     */
    public void buildPatch(@NotNull VcsRoot root,
                           @Nullable String fromVersion,
                           @NotNull String toVersion,
                           @NotNull PatchBuilder builder,
                           @NotNull CheckoutRules checkoutRules) throws VcsException {
        // Get the list of changes between versions
        List<ModificationData> changes;
        if (fromVersion == null) {
            // Full checkout - get all files at toVersion
            changes = Collections.emptyList();
            buildFullPatch(root, toVersion, builder, checkoutRules);
        } else {
            // Incremental patch - get changes between fromVersion and toVersion
            changes = collectChanges(root, fromVersion, toVersion, checkoutRules);
            buildIncrementalPatch(root, changes, builder, checkoutRules);
        }
    }

    private void buildFullPatch(@NotNull VcsRoot root,
                                 @NotNull String toVersion,
                                 @NotNull PatchBuilder builder,
                                 @NotNull CheckoutRules checkoutRules) throws VcsException {
        // Checkout the version in the workspace and add all files
        DiversionCommandExecutor executor = createExecutor(root);
        executor.checkout(toVersion, true);

        String workingDir = root.getProperty(DiversionSettings.WORKING_DIRECTORY);
        if (workingDir == null || workingDir.trim().isEmpty()) {
            throw new VcsException("Working directory is not configured");
        }

        File workDir = new File(workingDir);
        addDirectoryToPatch(workDir, "", builder, checkoutRules);
    }

    private void addDirectoryToPatch(@NotNull File dir,
                                      @NotNull String relativePath,
                                      @NotNull PatchBuilder builder,
                                      @NotNull CheckoutRules checkoutRules) throws VcsException {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            // Skip .dv directory
            if (file.getName().equals(".dv")) continue;

            String filePath = relativePath.isEmpty() ? file.getName() : relativePath + "/" + file.getName();

            if (file.isDirectory()) {
                addDirectoryToPatch(file, filePath, builder, checkoutRules);
            } else {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                    // Create a relative File object for the patch (not the absolute file)
                    File relativeFile = new File(filePath);
                    builder.createBinaryFile(relativeFile, filePath, fis, file.length());
                } catch (Exception e) {
                    throw new VcsException("Failed to add file to patch: " + filePath, e);
                }
            }
        }
    }

    private void buildIncrementalPatch(@NotNull VcsRoot root,
                                         @NotNull List<ModificationData> changes,
                                         @NotNull PatchBuilder builder,
                                         @NotNull CheckoutRules checkoutRules) throws VcsException {
        DiversionCommandExecutor executor = createExecutor(root);
        String workingDir = root.getProperty(DiversionSettings.WORKING_DIRECTORY);

        // Process each modification - checkout once per commit, not once per file
        for (ModificationData mod : changes) {
            executor.checkout(mod.getVersion(), true);

            for (VcsChange change : mod.getChanges()) {
                String filePath = change.getRelativeFileName();

                switch (change.getType()) {
                    case ADDED:
                        File addedFile = new File(workingDir, filePath);
                        if (!addedFile.isFile()) {
                            LOG.warn("Diversion.buildIncrementalPatch: skipping non-file ADDED entry: " + filePath);
                            break;
                        }
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(addedFile)) {
                            builder.createBinaryFile(new File(filePath), filePath, fis, addedFile.length());
                        } catch (Exception e) {
                            throw new VcsException("Failed to add file: " + filePath, e);
                        }
                        break;

                    case REMOVED:
                        try {
                            builder.deleteFile(new File(filePath), true);
                        } catch (Exception e) {
                            throw new VcsException("Failed to delete file: " + filePath, e);
                        }
                        break;

                    case CHANGED:
                        File changedFile = new File(workingDir, filePath);
                        if (!changedFile.isFile()) {
                            LOG.warn("Diversion.buildIncrementalPatch: skipping non-file CHANGED entry: " + filePath);
                            break;
                        }
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(changedFile)) {
                            builder.changeOrCreateBinaryFile(new File(filePath), filePath, fis, changedFile.length());
                        } catch (Exception e) {
                            throw new VcsException("Failed to change file: " + filePath, e);
                        }
                        break;
                }
            }
        }
    }

    @Nullable
    @Override
    public BuildPatchPolicy getBuildPatchPolicy() {
        // Return this to enable server-side checkout via patch building
        return this;
    }

    @NotNull
    @Override
    public DiversionCollectChangesPolicy getCollectChangesPolicy() {
        // Return separate policy instance, exactly like Git plugin does
        return new DiversionCollectChangesPolicy(this);
    }

    @Override
    public boolean isAgentSideCheckoutAvailable() {
        // Agent-side checkout is the ONLY supported checkout mode
        // Server-side checkout is not possible due to lack of diff support in Diversion CLI
        return true;
    }

    @Nullable
    @Override
    public VcsFileContentProvider getContentProvider() {
        // Return provider that checks out revisions and reads files from workspace
        return new DiversionFileContentProvider(this);
    }

    @Nullable
    @Override
    public LabelingSupport getLabelingSupport() {
        return new LabelingSupport() {
            @NotNull
            @Override
            public String label(@NotNull String label, @NotNull String version, @NotNull VcsRoot root, @NotNull CheckoutRules checkoutRules) throws VcsException {
                // Sanitize label name (replace spaces and special chars with underscores)
                String sanitizedLabel = label.trim().replace(' ', '_').replace('\t', '_').replace('\n', '_');

                DiversionCommandExecutor executor = createExecutor(root);
                executor.createTag(sanitizedLabel, version, null);

                return sanitizedLabel;
            }
        };
    }

    @Override
    public boolean sourcesUpdatePossibleIfChangesNotFound(@NotNull VcsRoot root) {
        // Return true to allow updates even when changes are not found
        return true;
    }

    @NotNull
    @Override
    public String describeVcsRoot(@NotNull VcsRoot root) {
        String repoId = root.getProperty(DiversionSettings.REPOSITORY_ID);
        String branch = root.getProperty(DiversionSettings.BRANCH_NAME, DiversionSettings.DEFAULT_BRANCH_NAME);
        return "Diversion repository: " + repoId + " (branch: " + branch + ")";
    }

    @NotNull
    @Override
    public Map<String, String> getDefaultVcsProperties() {
        Map<String, String> defaults = new HashMap<>();
        defaults.put(DiversionSettings.BRANCH_NAME, DiversionSettings.DEFAULT_BRANCH_NAME);
        defaults.put(DiversionSettings.DV_EXECUTABLE_PATH, DiversionSettings.DEFAULT_DV_EXECUTABLE);
        return defaults;
    }

    @NotNull
    @Override
    public PropertiesProcessor getVcsPropertiesProcessor() {
        return new AbstractVcsPropertiesProcessor() {
            @Override
            public Collection<InvalidProperty> process(Map<String, String> properties) {
                List<InvalidProperty> errors = new ArrayList<>();

                // Validate repository ID
                String repoId = properties.get(DiversionSettings.REPOSITORY_ID);
                if (repoId == null || repoId.trim().isEmpty()) {
                    errors.add(new InvalidProperty(DiversionSettings.REPOSITORY_ID,
                            "Repository ID is required"));
                } else if (!repoId.startsWith("dv.repo.")) {
                    errors.add(new InvalidProperty(DiversionSettings.REPOSITORY_ID,
                            "Repository ID must start with 'dv.repo.'"));
                }

                // Validate branch name
                String branch = properties.get(DiversionSettings.BRANCH_NAME);
                if (branch == null || branch.trim().isEmpty()) {
                    errors.add(new InvalidProperty(DiversionSettings.BRANCH_NAME,
                            "Branch name is required"));
                }

                // Validate executable path
                String dvPath = properties.get(DiversionSettings.DV_EXECUTABLE_PATH);
                if (dvPath == null || dvPath.trim().isEmpty()) {
                    errors.add(new InvalidProperty(DiversionSettings.DV_EXECUTABLE_PATH,
                            "Diversion executable path is required"));
                }

                return errors;
            }
        };
    }

    @NotNull
    public String getVersionDisplayName(@NotNull String version, @NotNull VcsRoot root) throws VcsException {
        // Display commit number and version
        int commitNum = DiversionLogParser.extractCommitNumber(version);
        return "Commit #" + commitNum + " (" + version + ")";
    }

    @NotNull
    public Comparator<String> getVersionComparator() {
        return new Comparator<String>() {
            @Override
            public int compare(String v1, String v2) {
                try {
                    int num1 = DiversionLogParser.extractCommitNumber(v1);
                    int num2 = DiversionLogParser.extractCommitNumber(v2);
                    return Integer.compare(num1, num2);
                } catch (VcsException e) {
                    return v1.compareTo(v2); // Fallback to string comparison
                }
            }
        };
    }

    @Nullable
    public String testConnection(@NotNull VcsRoot root) throws VcsException {
        try {
            DiversionCommandExecutor executor = createExecutor(root);
            executor.testConnection();
            return null; // Success - return null
        } catch (VcsException e) {
            return "Connection test failed: " + e.getMessage();
        }
    }

    // Helper method to create command executor
    @NotNull
    private DiversionCommandExecutor createExecutor(@NotNull VcsRoot root) {
        String dvPath = root.getProperty(DiversionSettings.DV_EXECUTABLE_PATH,
                DiversionSettings.DEFAULT_DV_EXECUTABLE);
        String workingDir = root.getProperty(DiversionSettings.WORKING_DIRECTORY);

        File workDir = null;
        if (workingDir != null && !workingDir.trim().isEmpty()) {
            workDir = new File(workingDir);
        }

        return new DiversionCommandExecutor(dvPath, workDir);
    }

    // Public helper method for DiversionCollectChangesPolicy
    @NotNull
    public DiversionCommandExecutor createExecutorForRoot(@NotNull VcsRoot root) {
        return createExecutor(root);
    }
}
