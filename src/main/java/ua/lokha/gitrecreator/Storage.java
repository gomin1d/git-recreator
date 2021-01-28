package ua.lokha.gitrecreator;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
@AllArgsConstructor
public class Storage {
    private List<StorageCommit> commits;
    private List<String> commitsQueue;

    private int branchId;

    public static GitRecreator from(Storage storage) {
        Map<String, Commit> commits = new HashMap<>();
        storage.getCommits().stream()
                .map(storageCommit -> new Commit(
                        storageCommit.getMessage(),
                        storageCommit.getOldHash(),
                        storageCommit.getNewHash(),
                        storageCommit.getAuthor(),
                        storageCommit.getAuthorDate(),
                        storageCommit.getCommitter(),
                        storageCommit.getCommitterDate(),
                        storageCommit.isMarkDelete()
                ))
                .forEach(commit -> commits.put(commit.getOldHash(), commit));
        for (StorageCommit storageCommit : storage.getCommits()) {
            Commit commit = commits.get(storageCommit.getOldHash());
            for (String storageParent : storageCommit.getParents()) {
                commit.getParents().add(commits.get(storageParent));
            }
            for (String storageChild : storageCommit.getChildren()) {
                commit.getChildren().add(commits.get(storageChild));
            }
        }
        LinkedList<Commit> queue = storage.getCommitsQueue().stream()
                .map(commits::get)
                .collect(Collectors.toCollection(LinkedList::new));
        return new GitRecreator(queue, commits, storage.getBranchId());
    }

    public static Storage to(GitRecreator recreator) {
        List<String> queue = recreator.getCommitsQueue().stream()
                .map(Commit::getOldHash)
                .collect(Collectors.toList());
        List<StorageCommit> commits = recreator.getCommits().values().stream()
                .map(commit -> new StorageCommit(
                        commit.getMessage(),
                        commit.getOldHash(),
                        commit.getNewHash(),
                        commit.getAuthor(),
                        commit.getAuthorDate(),
                        commit.getCommitter(),
                        commit.getCommitterDate(),
                        commit.isMarkDelete(),
                        commit.getParents().stream().map(Commit::getOldHash).collect(Collectors.toList()),
                        commit.getChildren().stream().map(Commit::getOldHash).collect(Collectors.toList())
                ))
                .collect(Collectors.toList());
        return new Storage(commits, queue, recreator.getBranchId());
    }
}
