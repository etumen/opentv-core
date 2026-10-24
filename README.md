# OpenTV Core

Shared Android library used by OpenTV TV and OpenTV Mobile.

It owns provider contracts/adapters, unified catalog matching and paging, episode/source failover,
provider health scoring, and provider-independent metadata enrichment.

The TV and mobile applications should consume the same pinned core commit so provider/domain fixes
are implemented once.

Licensed under the GNU General Public License v3.0 or later.
