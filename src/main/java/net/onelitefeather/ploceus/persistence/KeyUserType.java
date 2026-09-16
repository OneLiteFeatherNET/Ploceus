package net.onelitefeather.ploceus.persistence;

import net.onelitefeather.ploceus.domain.PloceusKey;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.usertype.EnhancedUserType;
import org.hibernate.usertype.UserType;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;

/// Hibernate basic-type mapping for {@link PloceusKey}, registered globally in
/// {@link HibernateBootstrap} via {@code Configuration#registerTypeOverride}.
///
/// A JPA {@link jakarta.persistence.AttributeConverter}
/// cannot be applied to identifier attributes - the JPA specification excludes {@code @Id}
/// from auto-apply conversion, and Hibernate enforces that exclusion even for globally
/// registered converters. {@code PloceusKey} is nevertheless the {@code @Id} type of
/// {@code Project}, {@code ProjectWorld} and {@code Tag}. A Hibernate-native {@link UserType}
/// carries no such restriction, applies uniformly to identifier and non-identifier
/// attributes alike, and needs no annotation on the domain entities.
///
/// Implements {@link EnhancedUserType} rather than the plain {@link UserType} contract because
/// PostgreSQL (unlike H2) reports {@code Dialect#useArrayForMultiValuedParameters() == true}:
/// Hibernate eagerly builds a native-array-parameter multi-id loader for every entity at
/// {@code SessionFactory} boot, and building that loader for a {@code PloceusKey}-keyed entity
/// (Project/ProjectWorld/Tag) requires a {@code JdbcLiteralFormatter} for {@code PloceusKey}.
/// A plain {@code UserType} cannot supply one - only {@code EnhancedUserType#toSqlLiteral} can.
/// Without it, {@code buildSessionFactory()} fails on PostgreSQL only, regardless of whether any
/// code ever actually loads entities by a batch of ids.
public class KeyUserType implements EnhancedUserType<PloceusKey> {

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

    /// Renders a {@code PloceusKey} as a SQL string literal, e.g. for inlining into generated
    /// SQL such as the PostgreSQL native-array multi-id loader. Adventure's {@code Key} grammar
    /// (namespace/value limited to {@code [a-z0-9._-]} plus {@code /} in the value) never
    /// produces a single quote, but the value is still escaped defensively by doubling any
    /// quote, per standard SQL string-literal escaping.
    @Override
    public String toSqlLiteral(PloceusKey value) {
        return "'" + value.asString().replace("'", "''") + "'";
    }

    @Override
    public String toString(PloceusKey value) {
        return value.asString();
    }

    @Override
    public PloceusKey fromStringValue(CharSequence sequence) {
        return sequence == null ? null : PloceusKey.parse(sequence.toString());
    }
}
