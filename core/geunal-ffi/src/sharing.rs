//! What this member shares with each group: the availability bitmap its calendars make (rust-core §5 "가용 시간
//! 올리기"), over the period and calendars the member chose for that group (design m2 §3 3, 11). The choice stays on
//! this device; only the bitmap leaves it, sealed.

use std::collections::{BTreeSet, HashMap};
use std::sync::Arc;

use chrono::{DateTime, Duration, FixedOffset, NaiveDate, NaiveTime, TimeZone};
use geunal_domain::KST;
use geunal_domain::calendar::availability_from_busy;
use serde::{Deserialize, Serialize};

use crate::local::{instances, kst_day};
use crate::{Core, CoreError};

/// Weeks shared when the member has not chosen (design m2 §3 3, kickoff §5.3).
pub const DEFAULT_WEEKS: u8 = 8;
/// The period a member may choose, in weeks (design m2 §3 11).
pub const MIN_WEEKS: u8 = 2;
pub const MAX_WEEKS: u8 = 13;

/// One group's sharing choice.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct GroupSharing {
    /// From today, 2 to 13.
    pub weeks: u8,
    /// The own calendars whose events make busy time; `None` for all of them, including ones made later. Soft
    /// calendars count as free whatever this says, and appointments of every group always count.
    pub calendar_ids: Option<Vec<String>>,
}

impl Default for GroupSharing {
    fn default() -> Self {
        Self { weeks: DEFAULT_WEEKS, calendar_ids: None }
    }
}

fn setting_key(group_id: &str) -> String {
    format!("sharing:{group_id}")
}

#[uniffi::export]
impl Core {
    /// The member's sharing choice for the group, the default until they change it.
    pub fn group_sharing(&self, group_id: String) -> Result<GroupSharing, CoreError> {
        self.sharing_of(&group_id)
    }

    /// Changes what this member shares with the group and sends the new bitmap. Invalid: weeks outside 2–13.
    pub async fn set_group_sharing(self: Arc<Self>, group_id: String, sharing: GroupSharing) -> Result<(), CoreError> {
        if !(MIN_WEEKS..=MAX_WEEKS).contains(&sharing.weeks) {
            return Err(CoreError::Invalid { reason: format!("sharing weeks {}", sharing.weeks) });
        }
        let text = serde_json::to_string(&sharing).map_err(|e| CoreError::Invalid { reason: e.to_string() })?;
        self.store.write(|db| Ok::<_, CoreError>(db.set_setting(&setting_key(&group_id), &text)?))?;
        let this = self.clone();
        self.run(async move { this.share_availability(Some(&group_id)).await }).await
    }
}

impl Core {
    fn sharing_of(&self, group_id: &str) -> Result<GroupSharing, CoreError> {
        let text = self.store.read(|db| db.setting(&setting_key(group_id)))?;
        // A choice this build cannot read falls back to the default rather than stopping the group's sharing.
        Ok(text.and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default())
    }

    /// Recomputes this member's bitmap for every group it is in (or just `only`) and sends the ones that changed. The
    /// window starts today in Asia/Seoul, so a new day moves it at the first sync of the day. Called at every `sync()`:
    /// an unchanged bitmap costs no request.
    pub(crate) async fn share_availability(&self, only: Option<&str>) -> Result<(), CoreError> {
        let today = kst_day(self.now());
        let from = midnight(today);
        let to = from + Duration::weeks(i64::from(MAX_WEEKS));
        // The calendars once for every group; each group then keeps the ones its choice names.
        let own = self.store.read(|db| {
            let calendars: HashMap<String, (bool, bool)> =
                db.calendars()?.into_iter().map(|c| (c.id, (c.blocks_free, c.kind == "group"))).collect();
            let busy =
                instances(db, from, to, |row| calendars.get(&row.calendar_id).is_some_and(|(blocks, _)| *blocks))?
                    .into_iter()
                    .map(|(row, o)| {
                        let group_calendar = calendars.get(&row.calendar_id).is_some_and(|(_, group)| *group);
                        (row.calendar_id, group_calendar, o.starts_at, o.ends_at)
                    })
                    .collect::<Vec<_>>();
            Ok::<_, CoreError>(busy)
        })?;
        let mut choices = HashMap::new();
        let only = only.map(str::to_owned);
        let groups: Vec<String> = self
            .engine(Box::new(|e| {
                Box::pin(async move {
                    let mut ids = Vec::new();
                    for g in e.store().groups()? {
                        if only.as_ref().is_none_or(|o| *o == g.id) && e.epoch(&g.id)?.is_some() {
                            ids.push(g.id);
                        }
                    }
                    Ok(ids)
                })
            }))
            .await?;
        for group in &groups {
            choices.insert(group.clone(), self.sharing_of(group)?);
        }
        self.engine(Box::new(move |e| {
            Box::pin(async move {
                let me = e.me().user_id.clone();
                // Appointments with a time, in any group, where this member leads or accepted: busy everywhere.
                let mut appointments = Vec::new();
                for g in e.store().groups()? {
                    for a in e.group_state(&g.id)?.appointments.into_values() {
                        if let (false, Some(start), Some(end), true) =
                            (a.cancelled, a.start, a.end, a.is_participant(&me))
                        {
                            appointments.push((at(a.day, start), at(a.day, end)));
                        }
                    }
                }
                let mut changed = Vec::new();
                for group in &groups {
                    let choice = &choices[group];
                    let named: Option<BTreeSet<&String>> = choice.calendar_ids.as_ref().map(|ids| ids.iter().collect());
                    let mut busy: Vec<(DateTime<FixedOffset>, DateTime<FixedOffset>)> = own
                        .iter()
                        .filter(|(calendar, group_calendar, _, _)| {
                            *group_calendar || named.as_ref().is_none_or(|n| n.contains(calendar))
                        })
                        .map(|(_, _, s, e)| (*s, *e))
                        .collect();
                    busy.extend(appointments.iter().copied());
                    let availability = availability_from_busy(today, u16::from(choice.weeks) * 7, &busy);
                    if e.share_availability(group, &availability)? {
                        changed.push(group.clone());
                    }
                }
                for group in changed {
                    e.flush_group(&group).await?;
                }
                Ok(())
            })
        }))
        .await
    }
}

fn midnight(day: NaiveDate) -> DateTime<FixedOffset> {
    KST.from_local_datetime(&day.and_time(NaiveTime::MIN)).single().expect("+09:00 has no gaps")
}

/// `minutes` after 00:00 Asia/Seoul on `day`, as an entry's appointment times are written (protocol §4).
fn at(day: NaiveDate, minutes: u16) -> DateTime<FixedOffset> {
    midnight(day) + Duration::minutes(i64::from(minutes))
}
