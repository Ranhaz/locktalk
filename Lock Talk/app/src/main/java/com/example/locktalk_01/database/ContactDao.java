
package com.example.locktalk_01.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.locktalk_01.models.Contact;

import java.util.List;

@Dao
public interface ContactDao {
    @Insert
    void insert(Contact contact);

    @Query("SELECT * FROM contacts")
    List<Contact> getAllContacts();
}
