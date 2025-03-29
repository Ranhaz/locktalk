
package com.example.locktalk_01.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.example.locktalk_01.models.User;

@Dao
public interface UserDao {
    @Insert
    void insert(User user);

    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    User getUserByUsername(String username);
}
