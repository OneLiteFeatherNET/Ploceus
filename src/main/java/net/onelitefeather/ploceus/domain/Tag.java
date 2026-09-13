package net.onelitefeather.ploceus.domain;

import jakarta.persistence.*;
import java.util.Objects;

@Entity
@Table(name = "ploceus_tag")
public class Tag {

    @Id
    @Column(name = "tag_key", length = 255)
    private PloceusKey key;

    protected Tag() { }

    public Tag(PloceusKey key) {
        this.key = key;
    }

    public PloceusKey getKey() { return key; }

    @Override
    public boolean equals(Object other) {
        return other instanceof Tag tag && Objects.equals(key, tag.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }
}
