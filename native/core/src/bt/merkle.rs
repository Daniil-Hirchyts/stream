//! BitTorrent v2 (BEP 52) Merkle trees: SHA-256 over 16 KiB blocks of each file. Leaves past the end of a file are
//! zero; a padded subtree hashes like any other, so padding above the leaves uses the hash of an all-zero subtree.

use sha2::{Digest, Sha256};

pub type Hash = [u8; 32];

pub const BLOCK: usize = 16 * 1024;
pub const ZERO: Hash = [0; 32];

pub fn sha256(data: &[u8]) -> Hash {
    Sha256::digest(data).into()
}

pub fn parent(left: &Hash, right: &Hash) -> Hash {
    let mut h = Sha256::new();
    h.update(left);
    h.update(right);
    h.finalize().into()
}

/// The root of an all-zero subtree `levels` above the leaves.
pub fn pad_hash(levels: u32) -> Hash {
    let mut h = ZERO;
    for _ in 0..levels {
        h = parent(&h, &h);
    }
    h
}

/// The root over `hashes` padded to `width` (a power of two) with `pad`, the hash of a padding entry at this layer.
pub fn root(hashes: &[Hash], width: usize, pad: Hash) -> Hash {
    debug_assert!(width.is_power_of_two() && hashes.len() <= width);
    let mut layer: Vec<Hash> = hashes.to_vec();
    let mut pad = pad;
    let mut width = width;
    while width > 1 {
        let mut next = Vec::with_capacity(layer.len().div_ceil(2));
        for pair in layer.chunks(2) {
            next.push(parent(&pair[0], pair.get(1).unwrap_or(&pad)));
        }
        pad = parent(&pad, &pad);
        layer = next;
        width /= 2;
    }
    layer.first().copied().unwrap_or(pad)
}

/// Leaf hashes of a run of file data: one per 16 KiB block, the last block hashed at its own length.
pub fn leaves(data: &[u8]) -> Vec<Hash> {
    data.chunks(BLOCK).map(sha256).collect()
}

/// The piece-layer entry for a piece of a file larger than one piece.
pub fn piece_hash(data: &[u8], piece_length: usize) -> Hash {
    root(&leaves(data), piece_length / BLOCK, ZERO)
}

/// The `pieces root` of a whole file.
pub fn file_root(data: &[u8], piece_length: usize) -> Hash {
    if data.len() <= piece_length {
        let l = leaves(data);
        return root(&l, l.len().next_power_of_two(), ZERO);
    }
    let layer: Vec<Hash> = data.chunks(piece_length).map(|p| piece_hash(p, piece_length)).collect();
    layer_root(&layer, piece_length)
}

/// The root a file's piece layer hashes to.
pub fn layer_root(layer: &[Hash], piece_length: usize) -> Hash {
    root(layer, layer.len().next_power_of_two(), pad_hash((piece_length / BLOCK).trailing_zeros()))
}

/// For answering a hash request: `length` piece-layer hashes from `index`, then the uncle hashes that prove their
/// subtree towards the root. As libtorrent counts them, `proof_layers` includes the layers inside the requested
/// subtree, so `proof_layers - log2(length)` uncles follow. None when the request is out of range.
pub fn answer(layer: &[Hash], piece_length: usize, index: usize, length: usize, proof_layers: usize) -> Option<Vec<Hash>> {
    let width = layer.len().next_power_of_two();
    if length == 0 || !length.is_power_of_two() || index % length != 0 || index + length > width {
        return None;
    }
    let pad = pad_hash((piece_length / BLOCK).trailing_zeros());
    let at = |i: usize| layer.get(i).copied().unwrap_or(pad);
    let mut out: Vec<Hash> = (index..index + length).map(at).collect();
    // Walk up from the requested subtree, adding each sibling.
    let mut nodes: Vec<Hash> = (0..width).map(at).collect();
    let mut pos = index / length;
    // Reduce the full layer to the requested subtree's level.
    let mut w = width;
    let mut l = 1;
    while l < length {
        nodes = nodes.chunks(2).map(|p| parent(&p[0], &p[1])).collect();
        w /= 2;
        l *= 2;
    }
    let uncles = proof_layers.saturating_sub(length.trailing_zeros() as usize);
    let mut added = 0;
    while w > 1 && added < uncles {
        out.push(nodes[pos ^ 1]);
        nodes = nodes.chunks(2).map(|p| parent(&p[0], &p[1])).collect();
        pos /= 2;
        w /= 2;
        added += 1;
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn small_files_hash_their_blocks_padded_to_a_power_of_two() {
        let data = vec![7u8; BLOCK * 3 - 5];
        let l = leaves(&data);
        assert_eq!(l.len(), 3);
        let expected = parent(&parent(&l[0], &l[1]), &parent(&l[2], &ZERO));
        assert_eq!(file_root(&data, 4 * BLOCK), expected);
    }

    #[test]
    fn large_files_hash_through_their_piece_layer() {
        let piece = 2 * BLOCK;
        let data: Vec<u8> = (0..piece * 2 + 100).map(|i| (i % 251) as u8).collect();
        let layer: Vec<Hash> = data.chunks(piece).map(|p| piece_hash(p, piece)).collect();
        assert_eq!(layer.len(), 3);
        // The same tree built straight from the leaves, zero-padded to a power of two.
        let l = leaves(&data);
        assert_eq!(file_root(&data, piece), root(&l, 8, ZERO));
        assert_eq!(layer_root(&layer, piece), root(&l, 8, ZERO));
    }

    #[test]
    fn answers_carry_uncles_that_prove_the_root() {
        let piece = BLOCK;
        let layer: Vec<Hash> = (0..6u8).map(|i| sha256(&[i])).collect();
        let full = layer_root(&layer, piece);
        let a = answer(&layer, piece, 2, 2, 3).unwrap();
        assert_eq!(a.len(), 2 + 2);
        let sub = parent(&a[0], &a[1]);
        // Subtree 1 of 4: its sibling is subtree 0, then the right half.
        let up = parent(&a[2], &sub);
        assert_eq!(parent(&up, &a[3]), full);
        assert!(answer(&layer, piece, 1, 2, 0).is_none());
    }
}
