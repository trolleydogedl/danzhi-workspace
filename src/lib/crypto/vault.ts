const DEVICE_KEY = "danzhi.device.v1";
const VAULT_KEY = "danzhi.vault.v1";

export type VaultPayload = {
  username: string;
  password: string;
  totpSecret?: string;
  cookies?: unknown;
};

function bytesToB64(buf: ArrayBuffer | Uint8Array): string {
  const u8 = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
  let s = "";
  for (let i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]!);
  return btoa(s);
}

function b64ToBytes(b64: string): Uint8Array {
  const s = atob(b64);
  const u8 = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) u8[i] = s.charCodeAt(i);
  return u8;
}

async function getDeviceKey(): Promise<CryptoKey> {
  const existing = localStorage.getItem(DEVICE_KEY);
  let raw: Uint8Array;
  if (existing) {
    raw = b64ToBytes(existing);
  } else {
    raw = crypto.getRandomValues(new Uint8Array(32));
    localStorage.setItem(DEVICE_KEY, bytesToB64(raw));
  }
  return crypto.subtle.importKey("raw", raw as BufferSource, "AES-GCM", false, [
    "encrypt",
    "decrypt",
  ]);
}

export async function saveVault(payload: VaultPayload): Promise<void> {
  const key = await getDeviceKey();
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encoded = new TextEncoder().encode(JSON.stringify(payload));
  const cipher = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: iv as BufferSource },
    key,
    encoded,
  );
  localStorage.setItem(
    VAULT_KEY,
    JSON.stringify({ iv: bytesToB64(iv), data: bytesToB64(cipher) }),
  );
}

export async function loadVault(): Promise<VaultPayload | null> {
  const raw = localStorage.getItem(VAULT_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as { iv: string; data: string };
    const key = await getDeviceKey();
    const iv = b64ToBytes(parsed.iv);
    const data = b64ToBytes(parsed.data);
    const plain = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: iv as BufferSource },
      key,
      data as BufferSource,
    );
    return JSON.parse(new TextDecoder().decode(plain)) as VaultPayload;
  } catch {
    return null;
  }
}

export function clearVault(): void {
  localStorage.removeItem(VAULT_KEY);
}

export async function patchVault(partial: Partial<VaultPayload>): Promise<void> {
  const current = (await loadVault()) ?? { username: "", password: "" };
  await saveVault({ ...current, ...partial });
}
