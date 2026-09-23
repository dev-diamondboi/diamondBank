# Deploy Diamond Bank: Vercel, Render and Neon

Vercel serves the static frontend. Render runs the Java API using the root `Dockerfile`. Neon stores PostgreSQL data, including sessions.

```text
Browser -> Vercel frontend
             /api/* -> Render Java API -> Neon PostgreSQL
```

## Render backend

Connect the repository to a Render Web Service using the Docker runtime. Keep the repository root as the root directory, use `./Dockerfile`, leave the Docker command unset, and use `/` as the health check path. Select the Free instance type if available for your account.

Set these environment variables on the Render service:

| Variable | Value |
| --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://YOUR_NEON_HOST/neondb?sslmode=require&channelBinding=require` |
| `DATABASE_USER` | Hosted database username |
| `DATABASE_PASSWORD` | Hosted database password |
| `PORT` | `8080` |
| `SERVER_ADDRESS` | `0.0.0.0` |
| `COOKIE_SECURE` | `true` |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=65` |

Use the host, database name and username from your Neon connection details. Java requires a JDBC URL with username and password in the separate variables above. The JDBC option is `channelBinding`, not the `channel_binding` option in Neon's libpq connection string. Do not include Markdown escape backslashes in any value. Store database credentials only in Render environment variables, never in frontend code or Git.

Deploy the service. Flyway applies database migrations at startup. The configured backend URL is [diamondbank.onrender.com](https://diamondbank.onrender.com/).

## Vercel frontend

Use the existing Vercel project with the repository root as its root directory. The frontend is plain HTML, CSS and JavaScript: use the Other framework preset, no build command, and the repository root (`.`) as the output directory. No Java or Node server starts on Vercel.

The root `vercel.json` forwards API requests to Render:

```json
{
  "$schema": "https://openapi.vercel.sh/vercel.json",
  "rewrites": [
    {
      "source": "/api/:path*",
      "destination": "https://diamondbank.onrender.com/api/:path*"
    }
  ]
}
```

The frontend already uses relative `/api/...` requests. This rewrite keeps browser requests on the Vercel origin, including session cookies and CSRF requests. No database credentials belong in Vercel. If the Render hostname changes, update the rewrite destination and redeploy Vercel.

After committing and pushing the configuration to the connected repository, redeploy Vercel. Backend changes also require a Render deployment; frontend or rewrite changes require a Vercel deployment. Preview deployments using this rewrite reach the same backend and database as production; use a separate backend and database if preview isolation is needed.

## Verify the hosted app

On the Vercel URL, open `/`, then `/banking.html?register`. Register a demo account, sign in, make a demo transfer, reload to confirm persistence, and sign out. If an API request fails, inspect its response in the browser Network panel and the Render service logs.

Local accounts are not uploaded. Each new hosted account gets its own demo balances. This remains a fictional portfolio bank.

The configuration is prepared locally. Successful deployment and the complete hosted sign-in/transfer flow still need verification.
