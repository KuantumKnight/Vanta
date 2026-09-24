import { admin } from "../_shared/admin.ts";

const projectId = Deno.env.get("FCM_PROJECT_ID") ?? "";
const clientEmail = Deno.env.get("FCM_CLIENT_EMAIL") ?? "";
const privateKey = Deno.env.get("FCM_PRIVATE_KEY") ?? "";
let cachedAccessToken = "";
let tokenExpiry = 0;

function base64Url(value: Uint8Array): string {
  let binary = "";
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

function decodePem(value: string): ArrayBuffer {
  const body = value.replace(/\\n/g, "\n").replace(/-----BEGIN PRIVATE KEY-----/g, "")
    .replace(/-----END PRIVATE KEY-----/g, "").replace(/\s/g, "");
  const bytes = Uint8Array.from(atob(body), (char) => char.charCodeAt(0));
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
}

async function serviceAccessToken(): Promise<string> {
  if (cachedAccessToken && Date.now() < tokenExpiry - 60000) return cachedAccessToken;
  if (!projectId || !clientEmail || !privateKey) throw new Error("FCM server credentials are not configured.");
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(new TextEncoder().encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = base64Url(new TextEncoder().encode(JSON.stringify({
    iss: clientEmail,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  })));
  const unsigned = header + "." + claims;
  const key = await crypto.subtle.importKey(
    "pkcs8", decodePem(privateKey),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  const assertion = unsigned + "." + base64Url(new Uint8Array(signature));
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  const body = await response.json();
  if (!response.ok || typeof body.access_token !== "string") {
    throw new Error("Could not authorize with Firebase Cloud Messaging.");
  }
  cachedAccessToken = body.access_token;
  tokenExpiry = Date.now() + Number(body.expires_in ?? 3600) * 1000;
  return cachedAccessToken;
}

export interface PushMessage {
  token: string;
  data: Record<string, string>;
  notification?: { title: string; body: string };
  priority?: "NORMAL" | "HIGH";
  channelId?: string;
}

export async function sendFcm(message: PushMessage): Promise<{ ok: boolean; permanent: boolean; error?: string }> {
  const bearer = await serviceAccessToken();
  const response = await fetch(
    "https://fcm.googleapis.com/v1/projects/" + encodeURIComponent(projectId) + "/messages:send",
    {
      method: "POST",
      headers: { authorization: "Bearer " + bearer, "content-type": "application/json" },
      body: JSON.stringify({
        message: {
          token: message.token,
          data: message.data,
          notification: message.notification,
          android: {
            priority: message.priority ?? "NORMAL",
            ttl: "1800s",
            collapse_key: message.notification ? undefined : "system-refresh",
            notification: message.channelId ? { channel_id: message.channelId } : undefined,
          },
        },
      }),
    },
  );
  if (response.ok) return { ok: true, permanent: false };
  const body = await response.text();
  const permanent = response.status === 404 || body.includes("UNREGISTERED") || body.includes("INVALID_ARGUMENT");
  return { ok: false, permanent, error: "FCM rejected the message (HTTP " + response.status + ")." };
}

export async function pushRefreshToUser(userId: string): Promise<void> {
  const { data: devices, error } = await admin.from("device_tokens")
    .select("id,token").eq("user_id", userId).eq("enabled", true);
  if (error) throw new Error("Could not load registered devices.");
  await Promise.all((devices ?? []).map(async (device) => {
    try {
      const result = await sendFcm({ token: device.token, data: { type: "refresh" } });
      if (!result.ok && result.permanent) {
        await admin.from("device_tokens").update({ enabled: false }).eq("id", device.id);
      }
    } catch {
      // Keep the token enabled; a later event can retry delivery.
    }
  }));
}
