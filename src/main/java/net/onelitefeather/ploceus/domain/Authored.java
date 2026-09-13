package net.onelitefeather.ploceus.domain;

import java.util.List;
import java.util.UUID;

public interface Authored {
    List<Author> getAuthors();
    void addAuthor(Author author);
    void removeAuthor(UUID playerUuid);
}
