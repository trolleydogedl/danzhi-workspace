import { createServerFn } from "@tanstack/react-start";
import type {
  CookieRecord,
  FilterPrefs,
  LoginResult,
  MfaChallenge,
  PollResult,
} from "./types";

export const startLoginFn = createServerFn({ method: "POST" })
  .validator(
    (d: { username: string; password: string; totpSecret?: string; totpCode?: string }) => d,
  )
  .handler(async ({ data }): Promise<LoginResult> => {
    const { startLogin } = await import("./engine.server");
    return startLogin(data);
  });

export const completeMfaFn = createServerFn({ method: "POST" })
  .validator((d: { challenge: MfaChallenge; code: string }) => d)
  .handler(async ({ data }): Promise<LoginResult> => {
    const { completeMfa } = await import("./engine.server");
    return completeMfa({ ...data, method: "userAndOtp" });
  });

export const pollAllFn = createServerFn({ method: "POST" })
  .validator(
    (d: {
      cookies: CookieRecord[];
      username: string;
      password?: string;
      totpSecret?: string;
      prefs?: FilterPrefs;
    }) => d,
  )
  .handler(async ({ data }): Promise<PollResult> => {
    const { pollAll } = await import("./engine.server");
    return pollAll(data);
  });

export const fetchQrFn = createServerFn({ method: "POST" })
  .validator((d: { cookies: CookieRecord[] }) => d)
  .handler(async ({ data }) => {
    const { fetchCampusQr } = await import("./engine.server");
    return fetchCampusQr(data.cookies);
  });
