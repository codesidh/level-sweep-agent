import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ConfigService } from '../services/config.service';
import { ChatRequest, ChatResponse, ConversationSummary, ConversationView } from '../models/assistant.model';

@Injectable({ providedIn: 'root' })
export class AssistantApi {
  private readonly http = inject(HttpClient);
  private readonly cfg = inject(ConfigService);

  // BFF route: POST /api/v1/assistant/chat
  //   Body shape: { tenantId, conversationId?, userMessage }
  //   Response:   { conversationId, turn: { role, content, ts, costUsd } }
  chat(req: { conversationId?: string | null; userMessage: string }): Observable<ChatResponse> {
    const body: ChatRequest = {
      tenantId: this.cfg.tenantId(),
      conversationId: req.conversationId ?? null,
      userMessage: req.userMessage,
    };
    return this.http.post<ChatResponse>(`${this.cfg.apiBase}/v1/assistant/chat`, body);
  }

  // BFF route: GET /api/v1/assistant/conversations?tenantId&limit
  conversations(limit = 20): Observable<ReadonlyArray<ConversationSummary>> {
    const params = new HttpParams().set('tenantId', this.cfg.tenantId()).set('limit', String(limit));
    return this.http.get<ReadonlyArray<ConversationSummary>>(`${this.cfg.apiBase}/v1/assistant/conversations`, {
      params,
    });
  }

  // BFF route: GET /api/v1/assistant/conversations/{id}?tenantId
  conversation(conversationId: string): Observable<ConversationView> {
    const params = new HttpParams().set('tenantId', this.cfg.tenantId());
    return this.http.get<ConversationView>(`${this.cfg.apiBase}/v1/assistant/conversations/${conversationId}`, {
      params,
    });
  }
}
