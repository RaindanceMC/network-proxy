package de.raindancer.proxy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * The network's PostgreSQL, as the proxy reaches it: a connection per question, short timeouts. The proxy asks
 * once per login, so a pool would only hold connections the database could give to the servers.
 */
public final class ProxyDatabase {

    private static final int TIMEOUT_SECONDS = 3;

    private final String url;
    private final Properties properties = new Properties();

    public ProxyDatabase(String host, int port, String database, String user, String password, String sslMode) {
        this.url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        properties.setProperty("user", user);
        properties.setProperty("password", password == null ? "" : password);
        properties.setProperty("sslmode", sslMode == null || sslMode.isBlank() ? "prefer" : sslMode);
        properties.setProperty("connectTimeout", String.valueOf(TIMEOUT_SECONDS));
        properties.setProperty("socketTimeout", String.valueOf(TIMEOUT_SECONDS));
        properties.setProperty("loginTimeout", String.valueOf(TIMEOUT_SECONDS));
        properties.setProperty("ApplicationName", "RainsNetworkProxy");
    }

    /** The driver straight, not DriverManager: shaded and relocated, it is registered with nobody. */
    public Connection connect() throws SQLException {
        Connection connection = new org.postgresql.Driver().connect(url, properties);
        if (connection == null) {
            throw new SQLException("not a PostgreSQL address: " + url);
        }
        return connection;
    }

    public String describe() {
        return url;
    }
}
