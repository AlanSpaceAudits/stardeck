//! The Seestar's storage as a guest SMB share ("EMMC Images"; the album root is MyWorks).
//! StarDeck puts its HUD copies in their own folder so they never mix with the scope's files.

use crate::proto::Res;

pub const SHARE: &str = "EMMC Images";
/// MyWorks\StarDeck; the SMB client takes `/` as the separator.
pub const FOLDER: &str = "MyWorks/StarDeck";

/// One guest session on the share; each call opens its own connection, as the scope's server drops
/// a connection after a file write.
fn with_share<T>(ip: &str, f: impl AsyncFnOnce(&mut smb2::SmbClient, &mut smb2::Tree) -> Res<T>) -> Res<T> {
    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build()?;
    rt.block_on(async {
        let mut client = smb2::connect(&format!("{ip}:445"), "", "").await?;
        let mut tree = client.connect_share(SHARE).await?;
        f(&mut client, &mut tree).await
    })
}

/// Copies `data` to MyWorks/StarDeck/`name` on the scope at `ip`; makes the folder when it is missing.
pub fn upload(ip: &str, name: &str, data: &[u8]) -> Res<()> {
    with_share(ip, async |client, tree| {
        if !client.stat(tree, FOLDER).await.is_ok_and(|i| i.is_directory) {
            client.create_directory(tree, FOLDER).await?;
        }
        client.write_file_pipelined(tree, &format!("{FOLDER}/{name}"), data).await?;
        Ok(())
    })
}

/// File names and sizes in MyWorks/StarDeck.
pub fn list(ip: &str) -> Res<Vec<(String, u64)>> {
    with_share(ip, async |client, tree| {
        let entries = client.list_directory(tree, &format!("{FOLDER}/")).await?;
        Ok(entries.into_iter().filter(|e| e.name != "." && e.name != "..").map(|e| (e.name, e.size)).collect())
    })
}

/// Removes MyWorks/StarDeck/`name`.
pub fn delete(ip: &str, name: &str) -> Res<()> {
    with_share(ip, async |client, tree| Ok(client.delete_file(tree, &format!("{FOLDER}/{name}")).await?))
}

#[cfg(test)]
mod tests {
    // Live check against the scope: cargo test -- --ignored share
    // Writes a 2 MB test file to MyWorks/StarDeck, lists it, and deletes it.
    #[test]
    #[ignore]
    fn share_upload_list_delete() {
        let ip = std::env::var("SEESTAR_IP").unwrap_or_else(|_| "192.168.0.79".into());
        let name = "stardeck_pc_test.bin";
        let data: Vec<u8> = (0..2_000_000u32).map(|i| (i % 251) as u8).collect();
        super::upload(&ip, name, &data).unwrap();
        let files = super::list(&ip).unwrap();
        assert!(files.contains(&(name.to_string(), data.len() as u64)), "{files:?}");
        super::delete(&ip, name).unwrap();
        assert!(!super::list(&ip).unwrap().iter().any(|(n, _)| n == name));
    }
}
