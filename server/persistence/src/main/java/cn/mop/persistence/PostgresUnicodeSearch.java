package cn.mop.persistence;

import java.sql.*;

/** Uses the already installed ICU root collation per expression; never changes database/global collation. */
final class PostgresUnicodeSearch {
    private PostgresUnicodeSearch() {}
    static boolean ready(Connection connection)throws SQLException{
        try(var statement=connection.prepareStatement("SELECT lower(U&'\\00C4' COLLATE \"pg_catalog\".\"und-x-icu\")=U&'\\00E4' AND lower(U&'\\039F\\03A3' COLLATE \"pg_catalog\".\"und-x-icu\")=U&'\\03BF\\03C2' AND lower(U&'\\0130' COLLATE \"pg_catalog\".\"und-x-icu\")=U&'i\\0307' AND lower(U&'\\212A' COLLATE \"pg_catalog\".\"und-x-icu\")='k'")){statement.setQueryTimeout(3);try(var rows=statement.executeQuery()){return rows.next()&&rows.getBoolean(1);}}
    }
}
