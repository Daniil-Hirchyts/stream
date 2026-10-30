//! Which interested peers to unchoke: the reciprocation scheme of BEP 3 and the mainline client. Regular slots go to
//! the peers that send us data fastest; once nothing more is wanted from the swarm (a streaming window that is
//! complete, or a seed), to the peers we upload to fastest. Peers that stopped sending while we wait on them
//! (snubbed) get no regular slot. One optimistic slot rotates among the other interested peers, newly connected ones
//! three times as likely, so new peers can show what they offer.

use std::time::Duration;

pub const SLOTS: usize = 8;
/// A peer that sent nothing for this long while we had requests outstanding with it is snubbed.
pub const SNUB: Duration = Duration::from_secs(60);
/// Peers connected for less than this are favoured for the optimistic slot.
pub const NEW_PEER: Duration = Duration::from_secs(60);

#[derive(Clone, Copy, Debug)]
pub struct Candidate {
    pub key: u64,
    pub interested: bool,
    /// Bytes per second received from it.
    pub down: i64,
    /// Bytes per second sent to it.
    pub up: i64,
    pub snubbed: bool,
    pub connected_for: Duration,
}

/// The peers to unchoke and the optimistic one among them. `current` is the optimistic peer chosen before; it is
/// kept unless `rotate` is set, it lost interest, or it earned a regular slot. `random` picks the new one.
pub fn choose(candidates: &[Candidate], seeding: bool, current: Option<u64>, rotate: bool, random: u64) -> (Vec<u64>, Option<u64>) {
    let mut ranked: Vec<&Candidate> = candidates.iter().filter(|c| c.interested && !c.snubbed).collect();
    ranked.sort_by_key(|c| (std::cmp::Reverse(if seeding { c.up } else { c.down }), c.key));
    let mut unchoked: Vec<u64> = ranked.iter().take(SLOTS - 1).map(|c| c.key).collect();
    let others: Vec<&Candidate> = candidates.iter().filter(|c| c.interested && !unchoked.contains(&c.key)).collect();
    let keep = current.filter(|k| !rotate && others.iter().any(|c| c.key == *k));
    let optimistic = keep.or_else(|| {
        let weight = |c: &Candidate| if c.connected_for < NEW_PEER { 3 } else { 1 };
        let total: u64 = others.iter().map(|c| weight(c)).sum();
        if total == 0 {
            return None;
        }
        let mut pick = random % total;
        others.iter().find(|c| {
            let w = weight(c);
            if pick < w {
                return true;
            }
            pick -= w;
            false
        }).map(|c| c.key)
    });
    if let Some(k) = optimistic {
        unchoked.push(k);
    }
    // With fewer interested peers than regular slots, a snubbed peer still gets a slot rather than none at all.
    for c in candidates.iter().filter(|c| c.interested && c.snubbed) {
        if unchoked.len() < SLOTS && !unchoked.contains(&c.key) {
            unchoked.push(c.key);
        }
    }
    (unchoked, optimistic)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn peer(key: u64, down: i64, up: i64) -> Candidate {
        Candidate { key, interested: true, down, up, snubbed: false, connected_for: Duration::from_secs(600) }
    }

    #[test]
    fn regular_slots_reward_the_fastest_senders_and_one_slot_is_optimistic() {
        let peers: Vec<Candidate> = (1..=12).map(|k| peer(k, k as i64 * 1000, 0)).collect();
        let (unchoked, optimistic) = choose(&peers, false, None, true, 0);
        assert_eq!(unchoked.len(), SLOTS);
        assert_eq!(&unchoked[..7], &[12, 11, 10, 9, 8, 7, 6]);
        let o = optimistic.unwrap();
        assert!((1..=5).contains(&o), "the optimistic peer is one without a regular slot");
        assert_eq!(*unchoked.last().unwrap(), o);
    }

    #[test]
    fn a_seed_ranks_by_upload_rate() {
        let peers: Vec<Candidate> = (1..=9).map(|k| peer(k, 100 - k as i64, k as i64 * 10)).collect();
        let (unchoked, _) = choose(&peers, true, None, true, 0);
        assert_eq!(&unchoked[..7], &[9, 8, 7, 6, 5, 4, 3]);
    }

    #[test]
    fn snubbed_peers_lose_their_regular_slot_unless_slots_are_spare() {
        let mut peers: Vec<Candidate> = (1..=10).map(|k| peer(k, k as i64 * 1000, 0)).collect();
        peers[9].snubbed = true; // the fastest, until it stopped sending
        let (unchoked, optimistic) = choose(&peers, false, None, true, 0);
        assert!(!unchoked[..7].contains(&10));
        assert!(optimistic != Some(10) || unchoked.len() == SLOTS);
        let few = vec![peer(1, 10, 0), Candidate { snubbed: true, ..peer(2, 0, 0) }];
        let (unchoked, _) = choose(&few, false, None, true, 0);
        assert!(unchoked.contains(&2), "spare slots still serve a snubbed peer");
    }

    #[test]
    fn the_optimistic_peer_is_kept_between_rotations_and_new_peers_are_favoured() {
        let mut peers: Vec<Candidate> = (1..=10).map(|k| peer(k, k as i64 * 1000, 0)).collect();
        let (_, first) = choose(&peers, false, None, true, 1);
        let (_, kept) = choose(&peers, false, first, false, 2);
        assert_eq!(kept, first, "kept until the next rotation");
        peers.iter_mut().find(|c| Some(c.key) == first).unwrap().interested = false;
        let (_, replaced) = choose(&peers, false, first, false, 2);
        assert_ne!(replaced, first, "a peer no longer interested is replaced at once");
        // Peers 1-3 lack regular slots; peer 1 is new, so it takes 3 of the 5 weights.
        let peers: Vec<Candidate> = (1..=10)
            .map(|k| Candidate { connected_for: Duration::from_secs(if k == 1 { 5 } else { 600 }), ..peer(k, k as i64 * 1000, 0) })
            .collect();
        let picks: Vec<u64> = (0..5).map(|r| choose(&peers, false, None, true, r).1.unwrap()).collect();
        assert_eq!(picks.iter().filter(|&&k| k == 1).count(), 3);
    }

    #[test]
    fn uninterested_peers_are_never_unchoked() {
        let peers = vec![Candidate { interested: false, ..peer(1, 9999, 0) }, peer(2, 1, 0)];
        let (unchoked, optimistic) = choose(&peers, false, None, true, 0);
        assert_eq!(unchoked, vec![2]);
        assert_eq!(optimistic, None, "peer 2 has a regular slot; nobody is left for the optimistic one");
    }
}
