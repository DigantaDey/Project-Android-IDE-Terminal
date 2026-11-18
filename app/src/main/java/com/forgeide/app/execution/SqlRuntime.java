package com.forgeide.app.execution;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.forgeide.core.language.SqlStatements;
import com.forgeide.core.util.*;
import java.util.*;
import java.util.function.Consumer;

/** Real SQLite, in-memory only, in the isolated service. The external watchdog bounds native execution. */
final class SqlRuntime {
    static int run(String source,int outputBytes,Consumer<String> stdout,Consumer<String> stderr){
        OutputLimiter output=new OutputLimiter(outputBytes,stdout);
        try(SQLiteDatabase database=SQLiteDatabase.create(null)){
            database.execSQL("PRAGMA temp_store=MEMORY");database.execSQL("PRAGMA foreign_keys=ON");
            for(String statement:SqlStatements.split(source)){
                String keyword=SqlStatements.keyword(statement);
                if(keyword.equals("ATTACH")||keyword.equals("DETACH"))throw new SecurityException("External databases are disabled; execution uses an in-memory database");
                if(Set.of("SELECT","WITH","PRAGMA","EXPLAIN").contains(keyword)){
                    try(Cursor cursor=database.rawQuery(statement,null)){
                        output.accept(String.join(" | ",cursor.getColumnNames())+"\n");int rows=0;
                        while(cursor.moveToNext()){
                            if(++rows>1000){output.accept("[row limit: 1000]\n");break;}
                            StringBuilder row=new StringBuilder();
                            for(int i=0;i<cursor.getColumnCount();i++){if(i>0)row.append(" | ");String value=cursor.isNull(i)?"NULL":cursor.getType(i)==Cursor.FIELD_TYPE_BLOB?"<blob>":cursor.getString(i);row.append(value.length()>1000?value.substring(0,1000)+"…":value);}
                            output.accept(row+"\n");
                        }
                        output.accept("("+Math.min(rows,1000)+" rows)\n");
                    }
                }else{database.execSQL(statement);output.accept(keyword+" OK\n");}
            }return 0;
        }catch(OutputLimiter.LimitExceeded e){stderr.accept(e.getMessage());return 124;}
        catch(Exception e){stderr.accept(String.valueOf(e.getMessage()));return 1;}
    }
}
