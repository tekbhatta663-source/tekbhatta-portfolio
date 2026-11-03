/** @type {import("next").NextConfig} */
const isPages = process.env.GITHUB_PAGES === "true";
const repo = "tekbhatta-portfolio";

const nextConfig = {
  reactStrictMode: true,
  ...(isPages
    ? {
        output: "export",
        trailingSlash: true,
        basePath: `/${repo}`,
        assetPrefix: `/${repo}/`,
        images: { unoptimized: true },
      }
    : {}),
};

export default nextConfig;
