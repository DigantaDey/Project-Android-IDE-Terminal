package com.forgeide.app.execution;

import androidx.sqlite.SQLite;
import androidx.sqlite.SQLiteConnection;
import androidx.sqlite.SQLiteStatement;
import androidx.sqlite.driver.bundled.BundledSQLiteDriver;
import com.forgeide.core.language.SqlStatements;
import com.forgeide.core.util.*;
import java.util.function.Consumer;

/** Real bundled SQLite, in-memory only, in the isolated service; no framework Settings provider access. */
final class SqlRuntime {

    static int run(String source, int outputBytes, Consumer<String> stdout, Consumer<String> stderr) {
        OutputLimiter output = new OutputLimiter(outputBytes, stdout);
        // Framework SQLite on some Android versions queries a Settings content provider during
        // construction. An isolated UID correctly cannot do that. Use AndroidX's bundled native
        // driver rather than granting app privileges, hidden APIs, reflection, or a resolver proxy.
        try (SQLiteConnection database = new BundledSQLiteDriver().open(":memory:")) {
            execute(database, "PRAGMA temp_store=MEMORY");
            execute(database, "PRAGMA foreign_keys=ON");
            for (String statement : SqlStatements.split(source)) {
                String keyword = SqlStatements.keyword(statement);
                if (keyword.equals("ATTACH") || keyword.equals("DETACH")) throw new SecurityException(
                    "External databases are disabled; execution uses an in-memory database"
                );
                try (SQLiteStatement query = database.prepare(statement)) {
                    int columns = query.getColumnCount();
                    if (columns == 0) {
                        while (query.step()) {}
                        output.accept(keyword + " OK\n");
                        continue;
                    }
                    StringBuilder header = new StringBuilder();
                    for (int i = 0; i < columns; i++) {
                        if (i > 0) header.append(" | ");
                        header.append(query.getColumnName(i));
                    }
                    output.accept(header + "\n");
                    int rows = 0;
                    while (query.step()) {
                        if (++rows > 1000) {
                            output.accept("[row limit: 1000]\n");
                            break;
                        }
                        StringBuilder row = new StringBuilder();
                        for (int i = 0; i < columns; i++) {
                            if (i > 0) row.append(" | ");
                            String value = query.isNull(i)
                                ? "NULL"
                                : query.getColumnType(i) == SQLite.SQLITE_DATA_BLOB
                                    ? "<blob>"
                                    : query.getText(i);
                            row.append(value.length() > 1000 ? value.substring(0, 1000) + "…" : value);
                        }
                        output.accept(row + "\n");
                    }
                    output.accept("(" + Math.min(rows, 1000) + " rows)\n");
                }
            }
            return 0;
        } catch (OutputLimiter.LimitExceeded limit) {
            stderr.accept(limit.getMessage());
            return 124;
        } catch (Exception error) {
            stderr.accept(error.toString());
            return 1;
        }
    }

    private static void execute(SQLiteConnection database, String sql) {
        try (SQLiteStatement statement = database.prepare(sql)) {
            while (statement.step()) {}
        }
    }
}
