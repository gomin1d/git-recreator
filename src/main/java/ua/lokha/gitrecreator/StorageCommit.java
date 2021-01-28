package ua.lokha.gitrecreator;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
public class StorageCommit {
    private String message;
    private String oldHash;
    private String newHash;
    private String author;
    private String authorDate;
    private String committer;
    private String committerDate;
    private boolean markDelete;
    private List<String> parents = new ArrayList<>();
    private List<String> children = new ArrayList<>();
}
