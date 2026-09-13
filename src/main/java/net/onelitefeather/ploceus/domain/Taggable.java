package net.onelitefeather.ploceus.domain;

import java.util.Set;

public interface Taggable {
    PloceusKey getKey();
    Set<Tag> getTags();
    void addTag(Tag tag);
    void removeTag(Tag tag);
}
