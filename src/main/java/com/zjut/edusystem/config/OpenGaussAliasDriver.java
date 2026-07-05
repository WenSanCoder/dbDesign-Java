package com.zjut.edusystem.config;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

public class OpenGaussAliasDriver implements Driver {
    private static final String OPENGAUSS_PREFIX = "jdbc:opengauss://";
    private static final String POSTGRESQL_PREFIX = "jdbc:postgresql://";

    private final org.postgresql.Driver delegate = new org.postgresql.Driver();

    static {
        try {
            DriverManager.registerDriver(new OpenGaussAliasDriver());
        } catch (SQLException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        return delegate.connect(toPostgresqlUrl(url), info);
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(OPENGAUSS_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return delegate.getPropertyInfo(toPostgresqlUrl(url), info);
    }

    @Override
    public int getMajorVersion() {
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return delegate.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return delegate.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    private String toPostgresqlUrl(String url) {
        if (url == null) {
            return null;
        }
        if (url.startsWith(OPENGAUSS_PREFIX)) {
            return POSTGRESQL_PREFIX + url.substring(OPENGAUSS_PREFIX.length());
        }
        return url;
    }
}
