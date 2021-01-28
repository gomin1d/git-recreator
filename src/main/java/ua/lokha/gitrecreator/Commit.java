package ua.lokha.gitrecreator;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Data
public class Commit {
    private String message;
    private String oldHash;
    private String newHash;
    private String author;
    private String authorDate;
    private String committer;
    private String committerDate;
    private boolean markDelete;
    private List<Commit> parents = new ArrayList<>();
    private List<Commit> children = new ArrayList<>();

    public Commit(String oldHash) {
        this.oldHash = oldHash;
    }

    public Commit(String message, String oldHash, String newHash, String author, String authorDate, String committer, String committerDate, boolean markDelete) {
        this.message = message;
        this.oldHash = oldHash;
        this.newHash = newHash;
        this.author = author;
        this.authorDate = authorDate;
        this.committer = committer;
        this.committerDate = committerDate;
        this.markDelete = markDelete;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Commit commit = (Commit) o;
        return oldHash.equals(commit.oldHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(oldHash);
    }

    @Override
    public String toString() {
        return "Commit{" +
                "message='" + message + '\'' +
                ", oldHash='" + oldHash + '\'' +
                ", newHash='" + newHash + '\'' +
                ", author='" + author + '\'' +
                ", authorDate='" + authorDate + '\'' +
                ", committer='" + committer + '\'' +
                ", committerDate='" + committerDate + '\'' +
                '}';
    }
}
