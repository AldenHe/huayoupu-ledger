package net.ankio.ledger.data

import android.database.sqlite.SQLiteDatabase

/** Removes v9/v10 metadata from disposable fixtures while retaining rows, references and indexes. */
internal object LegacyLedgerSchemaFixture {
    fun project(path:String,version:Int) {
        require(version in 1..9)
        SQLiteDatabase.openDatabase(path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            val removed=if(version<9)setOf("tags","requires_reimbursement") else setOf("requires_reimbursement")
            val columns=db.rawQuery("PRAGMA table_info(records)",null).use { c -> buildList {while(c.moveToNext())if(c.getString(1) !in removed)add(c.getString(1))} }
            val indexes=db.rawQuery("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='records' AND sql IS NOT NULL",null).use { c -> buildList {while(c.moveToNext())add(c.getString(0))} }
            val schema=db.rawQuery("SELECT sql FROM sqlite_master WHERE name='records'",null).use {it.moveToFirst();it.getString(0)}
                .replace(Regex(",\\s*requires_reimbursement INTEGER NOT NULL DEFAULT 0 CHECK\\(requires_reimbursement IN \\(0,1\\)\\)"),"")
                .let { if(version<9)it.replace(Regex(",\\s*tags TEXT NOT NULL DEFAULT ''"),"") else it }
                .replaceFirst("CREATE TABLE records","CREATE TABLE fixture_records")
            db.beginTransaction()
            try {
                db.execSQL(schema);db.execSQL("INSERT INTO fixture_records(${columns.joinToString()}) SELECT ${columns.joinToString()} FROM records")
                db.execSQL("DROP TABLE records");db.execSQL("ALTER TABLE fixture_records RENAME TO records");indexes.forEach { db.execSQL(it) }
                db.version=version;db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
}
