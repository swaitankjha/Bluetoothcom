package com.example.myapplication.mesh

import android.content.Context
import java.util.UUID

object NodeIdProvider {
    private const val PREFS_NAME = "mesh_prefs"
    private const val KEY_NODE_ID = "node_id"

    fun getNodeId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var nodeId = prefs.getString(KEY_NODE_ID, null)
        if (nodeId == null) {
            nodeId = UUID.randomUUID().toString().substring(0, 8) // Short ID for easier reading
            prefs.edit().putString(KEY_NODE_ID, nodeId).apply()
        }
        return nodeId
    }
}
