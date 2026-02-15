package com.example.stationops.data.repository

import com.example.stationops.data.model.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class AuthRepository {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    suspend fun login(email: String, pass: String): User {
        val authResult = auth.signInWithEmailAndPassword(email, pass).await()
        val uid = authResult.user?.uid ?: throw Exception("Login failed")
        val document = db.collection("users").document(uid).get().await()
        return document.toObject(User::class.java) ?: User(uid, email, "employee")
    }

    fun getCurrentUserId(): String? = auth.currentUser?.uid

    fun logout() {
        auth.signOut()
    }
}