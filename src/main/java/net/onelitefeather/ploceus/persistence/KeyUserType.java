package net.onelitefeather.ploceus.persistence;

import net.onelitefeather.ploceus.domain.PloceusKey;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.usertype.UserType;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;

/// Hibernate basic-type mapping for {@link PloceusKey}, registered globally in
/// {@link HibernateBootstrap} via {@code Configuration#registerTypeOverride}.
///
/// A JPA {@link jakarta.persistence.AttributeConverter} such as {@link KeyAttributeConverter}
/// cannot be applied to identifier attributes - the JPA specification excludes {@code @Id}
/// from auto-apply conversion, and Hibernate enforces that exclusion even for globally
/// registered converters. {@code PloceusKey} is nevertheless the {@code @Id} type of
/// {@code Project}, {@code ProjectWorld} and {@code Tag}. A Hibernate-native {@link UserType}
/// carries no such restriction, applies uniformly to identifier and non-identifier
/// attributes alike, and needs no annotation on the domain entities.
public class KeyUserType implements UserType<PloceusKey> {

    @Override
    public int getSqlType() {
        return Types.VARCHAR;
    }

    @Override
    public Class<PloceusKey> returnedClass() {
        return PloceusKey.class;
    }

    @Override
    public boolean equals(PloceusKey x, PloceusKey y) {
        return Objects.equals(x, y);
    }

    @Override
    public int hashCode(PloceusKey key) {
        return key == null ? 0 : key.hashCode();
    }

    @Override
    public PloceusKey nullSafeGet(ResultSet rs, int position, WrapperOptions options) throws SQLException {
        String stored = rs.getString(position);
        return rs.wasNull() ? null : PloceusKey.parse(stored);
    }

    @Override
    public void nullSafeSet(PreparedStatement st, PloceusKey value, int index, WrapperOptions options)
            throws SQLException {
        if (value == null) {
            st.setNull(index, Types.VARCHAR);
        } else {
            st.setString(index, value.asString());
        }
    }

    @Override
    public PloceusKey deepCopy(PloceusKey value) {
        return value;
    }

    @Override
    public boolean isMutable() {
        return false;
    }
}
