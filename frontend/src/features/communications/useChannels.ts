import { useEffect, useState } from 'react';
import { getChannels } from '@/api/communications';
import type { ChannelStatus } from '@/api/types';

/**
 * How messages reach customers, as the server says (2026-11-08); null while it is being asked, [] when it cannot be.
 */
export function useChannels(): ChannelStatus[] | null {
  const [channels, setChannels] = useState<ChannelStatus[] | null>(null);
  useEffect(() => {
    getChannels().then(setChannels, () => setChannels([]));
  }, []);
  return channels;
}

/**
 * Whether email is a way this deployment sends anything (2026-11-16). The user: "the email is not used so no need to be
 * shown" -- so while the server reports email OFF, which production does until a mail provider exists, every screen
 * leaves email out as a channel. Unknown counts as not in use: an email that might be sent is not shown as one that is.
 * Switching email on (MAIL_ENABLED) brings it back with no change here.
 */
export function emailInUse(channels: ChannelStatus[] | null): boolean {
  return (channels ?? []).some((c) => c.channel === 'EMAIL' && c.mode !== 'OFF');
}
