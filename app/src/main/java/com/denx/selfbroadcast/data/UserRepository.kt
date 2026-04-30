package com.denx.selfbroadcast.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class UserRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    fun saveCurrentUser(onDone: (Result<Unit>) -> Unit) {
        val user = auth.currentUser ?: run {
            onDone(Result.failure(IllegalStateException("User not signed in")))
            return
        }

        val data = hashMapOf(
            "uid" to user.uid,
            "email" to user.email,
            "displayName" to user.displayName,
            "photoUrl" to user.photoUrl?.toString(),
            "lastLoginAt" to FieldValue.serverTimestamp()
        )

        db.collection("users")
            .document(user.uid)
            .set(data, SetOptions.merge())
            .addOnSuccessListener { onDone(Result.success(Unit)) }
            .addOnFailureListener { onDone(Result.failure(it)) }
    }
}
