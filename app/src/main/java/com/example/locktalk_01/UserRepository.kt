package com.example.locktalk_01

import com.example.locktalk_01.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class UserRepository {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

    fun registerUser(username: String, password: String, callback: (Boolean) -> Unit) {
        val dummyEmail = "$username@example.com"
        auth.createUserWithEmailAndPassword(dummyEmail, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val userId = auth.currentUser?.uid
                    userId?.let {
                        val user=User(username,password)
                        saveUserToFirestore(it,user, callback)
                    }
                } else {
                    callback(false)
                }
            }
    }

    private fun saveUserToFirestore(userId: String, user:User, callback: (Boolean) -> Unit) {
        val userMap = hashMapOf(
            "userId" to userId,
            "username" to user.username,
            "password" to user.password
        )

        firestore.collection("Users").document(userId).set(userMap)
            .addOnSuccessListener { callback(true) }
            .addOnFailureListener { callback(false) }
    }
}