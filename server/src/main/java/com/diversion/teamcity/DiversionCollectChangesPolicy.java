package com.diversion.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.vcs.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Collect changes policy for Diversion VCS
 * Implements CollectChangesBetweenRepositories like Git plugin does
 */
public class DiversionCollectChangesPolicy implements CollectChangesBetweenRepositories {

    private static final Logger LOG = Logger.getInstance(DiversionCollectChangesPolicy.class.getName());

    private final DiversionVcsSupport myVcs;

    public DiversionCollectChangesPolicy(@NotNull DiversionVcsSupport vcs) {
        this.myVcs = vcs;
    }

    @NotNull
    @Override
    public List<ModificationData> collectChanges(@NotNull VcsRoot fromRoot,
                                                   @NotNull RepositoryStateData fromState,
                                                   @NotNull VcsRoot toRoot,
                                                   @NotNull RepositoryStateData toState,
                                                   @NotNull CheckoutRules checkoutRules) throws VcsException {
        // Extract version strings from repository states
        String fromVersion = fromState.getBranchRevisions().values().iterator().next();
        String toVersion = toState.getBranchRevisions().values().iterator().next();

        LOG.warn("Diversion.collectChanges: fromVersion=" + fromVersion + " toVersion=" + toVersion);

        // Parse commit numbers
        int fromCommitNum = DiversionLogParser.extractCommitNumber(fromVersion);
        int toCommitNum = DiversionLogParser.extractCommitNumber(toVersion);

        LOG.warn("Diversion.collectChanges: fromCommitNum=" + fromCommitNum + " toCommitNum=" + toCommitNum);

        if (fromCommitNum >= toCommitNum) {
            LOG.warn("Diversion.collectChanges: no new commits, returning empty");
            return Collections.emptyList(); // No new commits
        }

        // Get log of commits
        DiversionCommandExecutor executor = myVcs.createExecutorForRoot(toRoot);

        // Get branch name to checkout (to get out of detached state)
        String branchName = toRoot.getProperty(DiversionSettings.BRANCH_NAME, DiversionSettings.DEFAULT_BRANCH_NAME);

        // Checkout branch first (to get out of detached state if needed), discarding any local changes
        executor.checkout(branchName, true);

        // Update workspace to get latest changes from remote
        executor.update();

        int numCommits = toCommitNum - fromCommitNum;
        String logOutput = executor.getLog(numCommits + DiversionSettings.LOG_FETCH_BUFFER);

        // Parse log with executor for file change detection via dv ls
        DiversionLogParser parser = new DiversionLogParser(executor);
        List<ModificationData> allMods = parser.parse(logOutput, toRoot);

        // Filter to only commits between fromVersion and currentVersion
        List<ModificationData> filteredMods = new ArrayList<>();
        for (ModificationData mod : allMods) {
            try {
                int modCommitNum = DiversionLogParser.extractCommitNumber(mod.getVersion());
                if (modCommitNum > fromCommitNum && modCommitNum <= toCommitNum) {
                    filteredMods.add(mod);
                }
            } catch (VcsException e) {
                LOG.warn("Error extracting commit number from " + mod.getVersion() + ": " + e.getMessage());
                // Skip commits with invalid IDs
            }
        }

        // Sort by commit number (oldest first)
        filteredMods.sort(new Comparator<ModificationData>() {
            @Override
            public int compare(ModificationData o1, ModificationData o2) {
                try {
                    int num1 = DiversionLogParser.extractCommitNumber(o1.getVersion());
                    int num2 = DiversionLogParser.extractCommitNumber(o2.getVersion());
                    return Integer.compare(num1, num2);
                } catch (VcsException e) {
                    return 0;
                }
            }
        });

        return filteredMods;
    }

    @NotNull
    @Override
    public List<ModificationData> collectChanges(@NotNull VcsRoot root,
                                                   @NotNull RepositoryStateData fromState,
                                                   @NotNull RepositoryStateData toState,
                                                   @NotNull CheckoutRules checkoutRules) throws VcsException {
        // Single repository case
        return collectChanges(root, fromState, root, toState, checkoutRules);
    }

    @NotNull
    @Override
    public RepositoryStateData getCurrentState(@NotNull VcsRoot root) throws VcsException {
        LOG.warn("Diversion.getCurrentState: polling for current state");
        String currentVersion = myVcs.getCurrentVersion(root);
        LOG.warn("Diversion.getCurrentState: currentVersion=" + currentVersion);
        return RepositoryStateData.createSingleVersionState(currentVersion);
    }

    @NotNull
    public RepositoryStateData fetchAllRefs(@NotNull VcsRoot root,
                                             @NotNull CheckoutRules checkoutRules) throws VcsException {
        // For Diversion, we only track single branch
        return getCurrentState(root);
    }
}
