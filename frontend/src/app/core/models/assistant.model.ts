// Mirrors com.levelsweep.aiagent.assistant.AssistantResource DTOs:
//   POST /api/v1/assistant/chat                       → ChatResponse
//   GET  /api/v1/assistant/conversations              → ConversationSummary[]
//   GET  /api/v1/assistant/conversations/{id}         → ConversationView

export type AssistantRole = 'user' | 'assistant';

export interface AssistantTurn {
  readonly role: AssistantRole;
  readonly content: string;
  readonly ts: string;
  readonly costUsd: string | number;
}

export interface ChatRequest {
  readonly tenantId: string;
  readonly conversationId?: string | null;
  readonly userMessage: string;
}

export interface ChatResponse {
  readonly conversationId: string;
  readonly turn: AssistantTurn;
}

export interface ConversationSummary {
  readonly conversationId: string;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly turnCount: number;
  readonly totalCostUsd: string | number;
}

export interface ConversationView {
  readonly conversationId: string;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly turns: ReadonlyArray<AssistantTurn>;
  readonly totalCostUsd: string | number;
}
