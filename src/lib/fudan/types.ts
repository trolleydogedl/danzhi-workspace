export type SourceId = "math" | "jwc" | "elearning" | "ehall" | "mail";

export type ItemKind =
  | "notice"
  | "announcement"
  | "ddl"
  | "exam"
  | "todo"
  | "mail";

export type Priority = "urgent" | "academic" | "admin" | "noise";

export type CookieRecord = {
  name: string;
  value: string;
  domain: string;
  path: string;
};

export type Profile = {
  studentId: string;
  name: string;
  email?: string;
  college?: string;
};

export type FeedItem = {
  id: string;
  source: SourceId;
  kind: ItemKind;
  title: string;
  summary: string;
  url: string;
  publishedAt: string;
  dueAt?: string;
  course?: string;
  score: number;
  tags: string[];
  filtered: boolean;
  filterReason?: string;
  priority: Priority;
};

export type CourseMeeting = {
  id: string;
  name: string;
  teacher?: string;
  location?: string;
  day: number;
  startPeriod: number;
  endPeriod: number;
  weeks?: string;
  weeksList?: number[];
};

export type SourceReport = {
  ok: boolean;
  count: number;
  error?: string;
  ms: number;
};

export type PollResult = {
  items: FeedItem[];
  courses: CourseMeeting[];
  profile?: Profile;
  sources: Record<string, SourceReport>;
  polledAt: string;
  needReauth?: boolean;
  cookies?: CookieRecord[];
  loginNote?: string;
};

export type MfaMethod = "userAndOtp";

export type MfaChallenge = {
  lck: string;
  requestNumber: string;
  authChainCode: string;
  entityId: string;
  requestType: string;
  username: string;
  modules: MfaMethod[];
  cookies: CookieRecord[];
  referer: string;
  service: string;
};

export type LoginOk = {
  status: "ok";
  cookies: CookieRecord[];
  profile: Profile;
  note?: string;
};

export type LoginMfa = {
  status: "mfa";
  challenge: MfaChallenge;
};

export type LoginResult = LoginOk | LoginMfa;

export type FilterPrefs = {
  dropAid: boolean;
  dropYouthLeague: boolean;
  dropStudentAffairs: boolean;
  dropAdminReport: boolean;
  keepMathBoost: boolean;
  keepDeadlineBoost: boolean;
};
