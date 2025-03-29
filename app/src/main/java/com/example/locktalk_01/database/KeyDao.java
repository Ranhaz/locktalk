
package com.example.locktalk_01.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.locktalk_01.models.EncryptionKey;

import java.util.List;

@Dao
public interface KeyDao {
    @Insert
    void insert(EncryptionKey key);

    @Query("SELECT * FROM encryption_keys WHERE contactName = :contactName LIMIT 1")
    EncryptionKey getKeyForContact(String contactName);

    @Query("SELECT * FROM encryption_keys")
    List<EncryptionKey> getAllKeys();
}
