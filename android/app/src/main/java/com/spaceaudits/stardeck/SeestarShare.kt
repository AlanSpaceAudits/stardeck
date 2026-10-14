package com.spaceaudits.stardeck

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.InputStream

/**
 * The Seestar's storage as a guest SMB share ("EMMC Images", the album root is MyWorks).
 * StarDeck puts its HUD copies in their own folder so they never mix with the scope's files.
 */
object SeestarShare {
    const val SHARE = "EMMC Images"
    const val FOLDER = "MyWorks\\StarDeck"

    /** Copies `input` to MyWorks/StarDeck/`name` on the scope at `ip`. */
    fun upload(ip: String, name: String, input: InputStream) {
        SMBClient().use { client ->
            client.connect(ip).use { conn ->
                val session = conn.authenticate(AuthenticationContext.guest())
                (session.connectShare(SHARE) as DiskShare).use { share ->
                    if (!share.folderExists(FOLDER)) share.mkdir(FOLDER)
                    share.openFile(
                        "$FOLDER\\$name", setOf(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
                    ).use { f -> f.outputStream.use { input.copyTo(it) } }
                }
            }
        }
    }

    /** Removes MyWorks/StarDeck/`name`; for tests. */
    fun delete(ip: String, name: String) {
        SMBClient().use { client ->
            client.connect(ip).use { conn ->
                (conn.authenticate(AuthenticationContext.guest()).connectShare(SHARE) as DiskShare).use { it.rm("$FOLDER\\$name") }
            }
        }
    }
}
