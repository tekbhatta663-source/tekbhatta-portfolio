/** Types for the plain-JS server handlers shared by Vite and serve.mjs. */
import type { IncomingMessage, ServerResponse } from "node:http";

export declare function handleApiRequest(
  req: IncomingMessage,
  res: ServerResponse,
): Promise<void>;

export declare function isSafeUrl(raw: unknown): boolean;
