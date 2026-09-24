package com.example.myapplication

import com.example.myapplication.mesh.MeshPacket
import com.example.myapplication.mesh.PacketType
import org.junit.Assert.*
import org.junit.Test

class MeshManagerTest {

    @Test
    fun testPacketCreationAndTtl() {
        val packet = MeshPacket(
            senderId = "nodeA",
            destinationId = "broadcast",
            type = PacketType.SOS,
            payload = "Emergency test",
            ttl = 5
        )
        
        assertEquals("nodeA", packet.senderId)
        assertEquals("broadcast", packet.destinationId)
        assertEquals(PacketType.SOS, packet.type)
        assertEquals(5, packet.ttl)

        val relayed = packet.copy(ttl = packet.ttl - 1)
        assertEquals(4, relayed.ttl)
    }
}
