/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // The browser-facing API base URL is provided via NEXT_PUBLIC_API_BASE_URL (see .env.local.example).
  // Optional dev-time proxy so same-origin "/api/*" calls reach the FastAPI backend.
  async rewrites() {
    const apiBase = process.env.BACKEND_INTERNAL_URL || "http://localhost:8000";
    return [
      {
        source: "/api/:path*",
        destination: `${apiBase}/api/:path*`,
      },
    ];
  },
};

module.exports = nextConfig;
