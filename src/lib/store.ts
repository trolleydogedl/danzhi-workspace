import { create } from "zustand";
import { persist } from "zustand/middleware";
import type {
  CookieRecord,
  CourseMeeting,
  FeedItem,
  FilterPrefs,
  MfaChallenge,
  PollResult,
  Profile,
  SourceReport,
} from "./fudan/types";
import { DEFAULT_FILTER_PREFS } from "./fudan/filter";
import { DEFAULT_WEEK_START } from "./fudan/periods";
import { applyFilter } from "./fudan/filter";
import { buildDemoItems, DEMO_COURSES, DEMO_PROFILE } from "./fudan/demo-data";

export type TabId = "feed" | "table" | "pass" | "rooms" | "notes" | "settings";
export type Phase = "boot" | "login" | "mfa" | "app";

type AppState = {
  phase: Phase;
  tab: TabId;
  isDemo: boolean;
  profile: Profile | null;
  items: FeedItem[];
  courses: CourseMeeting[];
  cookies: CookieRecord[];
  challenge: MfaChallenge | null;
  readIds: string[];
  seenIds: string[];
  showNoise: boolean;
  filterPrefs: FilterPrefs;
  weekStart: string;
  pollIntervalMin: number;
  lastPollAt: string | null;
  sources: Record<string, SourceReport>;
  polling: boolean;
  pollError: string | null;
  notifyEnabled: boolean;
  ntfyTopic: string;
  loginNote: string | null;
  setTab: (tab: TabId) => void;
  setPhase: (phase: Phase) => void;
  setChallenge: (c: MfaChallenge | null) => void;
  enterDemo: () => void;
  applySession: (profile: Profile, cookies: CookieRecord[]) => void;
  applyPoll: (result: PollResult) => string[];
  markRead: (id: string) => void;
  setShowNoise: (v: boolean) => void;
  setFilterPrefs: (p: Partial<FilterPrefs>) => void;
  setPolling: (v: boolean) => void;
  setPollError: (e: string | null) => void;
  setNotifyEnabled: (v: boolean) => void;
  setNtfyTopic: (v: string) => void;
  setPollInterval: (n: number) => void;
  logout: () => void;
};

export const useAppStore = create<AppState>()(
  persist(
    (set, get) => ({
      phase: "login",
      tab: "feed",
      isDemo: false,
      profile: null,
      items: [],
      courses: [],
      cookies: [],
      challenge: null,
      readIds: [],
      seenIds: [],
      showNoise: false,
      filterPrefs: DEFAULT_FILTER_PREFS,
      weekStart: DEFAULT_WEEK_START,
      pollIntervalMin: 10,
      lastPollAt: null,
      sources: {},
      polling: false,
      pollError: null,
      notifyEnabled: false,
      ntfyTopic: "",
      loginNote: null,
      setTab: (tab) => set({ tab }),
      setPhase: (phase) => set({ phase }),
      setChallenge: (challenge) => set({ challenge, phase: challenge ? "mfa" : "login" }),
      enterDemo: () =>
        set({
          phase: "app",
          isDemo: true,
          profile: DEMO_PROFILE,
          items: applyFilter(buildDemoItems(), get().filterPrefs),
          courses: DEMO_COURSES,
          cookies: [],
          lastPollAt: new Date().toISOString(),
          sources: {
            math: { ok: true, count: 3, ms: 12 },
            jwc: { ok: true, count: 2, ms: 9 },
            elearning: { ok: true, count: 4, ms: 18 },
            ehall: { ok: true, count: 1, ms: 7 },
            mail: { ok: true, count: 1, ms: 11 },
          },
          pollError: null,
        }),
      applySession: (profile, cookies) =>
        set({
          phase: "app",
          isDemo: false,
          profile,
          cookies,
          challenge: null,
          loginNote: null,
        }),
      applyPoll: (result) => {
        const prefs = get().filterPrefs;
        const items = applyFilter(result.items, prefs);
        const prevSeen = new Set(get().seenIds);
        const fresh = items.filter((it) => !it.filtered && !prevSeen.has(it.id));
        const seenIds = [...new Set([...get().seenIds, ...items.map((i) => i.id)])];
        set({
          items,
          courses: result.courses.length ? result.courses : get().courses,
          profile: result.profile ?? get().profile,
          cookies: result.cookies?.length ? result.cookies : get().cookies,
          lastPollAt: result.polledAt,
          sources: result.sources,
          pollError: result.needReauth
            ? result.loginNote || "会话过期，请重新登录"
            : null,
          seenIds,
          isDemo: false,
          loginNote: result.loginNote ?? get().loginNote,
        });
        return fresh
          .filter((i) => i.priority === "urgent" || i.priority === "academic")
          .map((i) => i.id);
      },
      markRead: (id) => set({ readIds: [...new Set([...get().readIds, id])] }),
      setShowNoise: (showNoise) => set({ showNoise }),
      setFilterPrefs: (p) => {
        const filterPrefs = { ...get().filterPrefs, ...p };
        set({
          filterPrefs,
          items: applyFilter(get().items, filterPrefs),
        });
      },
      setPolling: (polling) => set({ polling }),
      setPollError: (pollError) => set({ pollError }),
      setNotifyEnabled: (notifyEnabled) => set({ notifyEnabled }),
      setNtfyTopic: (ntfyTopic) => set({ ntfyTopic }),
      setPollInterval: (pollIntervalMin) => set({ pollIntervalMin }),
      logout: () =>
        set({
          phase: "login",
          isDemo: false,
          profile: null,
          items: [],
          courses: [],
          cookies: [],
          challenge: null,
          readIds: [],
          seenIds: [],
          lastPollAt: null,
          sources: {},
          pollError: null,
          loginNote: null,
        }),
    }),
    {
      name: "danzhi-store-v2",
      skipHydration: true,
      partialize: (s) => ({
        phase: s.phase === "mfa" ? "login" : s.phase,
        tab: s.tab,
        isDemo: s.isDemo,
        profile: s.profile,
        items: s.items,
        courses: s.courses,
        cookies: s.cookies,
        readIds: s.readIds,
        seenIds: s.seenIds,
        showNoise: s.showNoise,
        filterPrefs: s.filterPrefs,
        weekStart: s.weekStart,
        pollIntervalMin: s.pollIntervalMin,
        lastPollAt: s.lastPollAt,
        sources: s.sources,
        notifyEnabled: s.notifyEnabled,
        ntfyTopic: s.ntfyTopic,
      }),
    },
  ),
);
