//@ts-check

/** @type {import('next').NextConfig} */
const nextConfig = {
  // Self-contained server output, enabled in the container build (apps/chatdashboardapp/Dockerfile).
  // Not enabled for host builds: on Windows, standalone tracing needs symlink privileges (os error 1314).
  output: process.env.NEXT_OUTPUT === 'standalone' ? 'standalone' : undefined,
  poweredByHeader: false,
  // Browser-visible config (API/widget URLs) is read at request time from PUBLIC_* env vars in page.tsx,
  // so one image works in every environment. Never put secrets in PUBLIC_* variables.
};

module.exports = nextConfig;
