//! Side-by-side layout of overlapping timed events in one day of the week view (docs/design/m1.md screen 7: "겹치는
//! 일정은 나란히 나눠 그림(최대 3열, 넘으면 '+N')"). Both apps draw the columns this returns, so the same day looks the
//! same on both.

/// Where one block goes: column `column` of `columns` equal columns.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Place {
    pub column: u8,
    pub columns: u8,
}

/// Places the blocks `[start, end)` (minutes of the day), at most `max` columns side by side. Overlapping blocks form
/// a cluster; each block takes the leftmost column free at its start, and every block of a cluster shares the
/// cluster's column count. A block that would need a column past `max` gets `None`: it is counted in the "+N".
/// Blocks are visited by start, longer first on a tie, so the same input always gives the same picture.
pub fn place(blocks: &[(u16, u16)], max: u8) -> Vec<Option<Place>> {
    let mut order: Vec<usize> = (0..blocks.len()).collect();
    order.sort_by_key(|&i| (blocks[i].0, std::cmp::Reverse(blocks[i].1), i));
    let mut out: Vec<Option<Place>> = vec![None; blocks.len()];
    let mut cluster: Vec<(usize, usize)> = Vec::new(); // (block, column) of the cluster being built
    let mut column_ends: Vec<u16> = Vec::new(); // end of the last block in each column of the cluster
    let mut cluster_end = 0u16;

    let close = |cluster: &mut Vec<(usize, usize)>, column_ends: &mut Vec<u16>, out: &mut Vec<Option<Place>>| {
        let used = column_ends.len().min(usize::from(max)) as u8;
        for &(block, column) in cluster.iter() {
            out[block] = (column < usize::from(max)).then_some(Place { column: column as u8, columns: used });
        }
        cluster.clear();
        column_ends.clear();
    };

    for i in order {
        let (start, end) = blocks[i];
        if !cluster.is_empty() && start >= cluster_end {
            close(&mut cluster, &mut column_ends, &mut out);
        }
        let column = match column_ends.iter().position(|&e| e <= start) {
            Some(c) => {
                column_ends[c] = end;
                c
            }
            None => {
                column_ends.push(end);
                column_ends.len() - 1
            }
        };
        cluster_end = if cluster.is_empty() { end } else { cluster_end.max(end) };
        cluster.push((i, column));
    }
    if !cluster.is_empty() {
        close(&mut cluster, &mut column_ends, &mut out);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(column: u8, columns: u8) -> Option<Place> {
        Some(Place { column, columns })
    }

    #[test]
    fn gives_a_lone_event_the_whole_width() {
        assert_eq!(place(&[(600, 720)], 3), vec![p(0, 1)]);
    }

    #[test]
    fn splits_overlapping_events_and_frees_a_column_when_one_ends() {
        // 10–12 and 11–13 overlap; 12–14 starts when the first ends and takes its column again.
        assert_eq!(place(&[(600, 720), (660, 780), (720, 840)], 3), vec![p(0, 2), p(1, 2), p(0, 2)]);
    }

    #[test]
    fn hides_the_fourth_overlapping_event_behind_the_plus_n() {
        let blocks = [(600, 720), (600, 720), (600, 720), (600, 720)];
        let placed = place(&blocks, 3);
        assert_eq!(placed.iter().filter(|p| p.is_none()).count(), 1);
        assert!(placed.iter().flatten().all(|p| p.columns == 3));
    }

    #[test]
    fn starts_a_new_cluster_after_a_gap() {
        assert_eq!(place(&[(600, 660), (630, 690), (900, 960)], 3), vec![p(0, 2), p(1, 2), p(0, 1)]);
    }

    #[test]
    fn puts_the_longer_of_two_events_with_the_same_start_on_the_left() {
        assert_eq!(place(&[(600, 660), (600, 780)], 3), vec![p(1, 2), p(0, 2)]);
    }
}
