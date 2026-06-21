export const SUPABASE_URL = import.meta.env.VITE_SUPABASE_URL ?? "";
export const SUPABASE_ANON_KEY = import.meta.env.VITE_SUPABASE_ANON_KEY ?? "";
export const MOBILECORE_BASE_URL = import.meta.env.VITE_MOBILECORE_BASE_URL ?? "http://127.0.0.1:8080/v1";
export const MOBILECORE_API_KEY = import.meta.env.VITE_MOBILECORE_API_KEY ?? "local";
export const MOBILECORE_SIGNATURE_SECRET = import.meta.env.VITE_MOBILECORE_SIGNATURE_SECRET ?? MOBILECORE_API_KEY;

export const isSupabaseConfigured = Boolean(SUPABASE_URL && SUPABASE_ANON_KEY);
