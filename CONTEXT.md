# Jev Relationship Assistant Context

This context defines the product language for analyzing conversations and retaining user-controlled relationship context. It describes concepts, not implementation details.

## Conversation

**Conversation**:
A bounded set of messages the user chooses to analyze, including its optional relationship context.
_Avoid_: Chat session, transcript when referring to the full user-selected unit

**Message**:
A single utterance attributed to a speaker within a Conversation.
_Avoid_: Event when the user means message content

**Conversation Source**:
The user-approved origin of a Conversation, such as manual input, file import, or an accessibility surface.
_Avoid_: Ingestion pipeline when discussing user-visible provenance

## Analysis

**Analysis**:
A structured interpretation of a Conversation containing emotion, intent probabilities, communication risk, and advice.
_Avoid_: Truth, diagnosis, verdict

**Intent**:
A plausible purpose behind a message, represented with a confidence value rather than as a fact.
_Avoid_: Mind reading, conclusion

**Communication Risk**:
A bounded indicator of how likely an uncareful response is to escalate misunderstanding or conflict.
_Avoid_: Danger, mental-health risk

**Reply Plan**:
A generated response option labeled by tone and grounded in the current Analysis.
_Avoid_: Auto-reply, sent message

## Relationship Memory

**Contact**:
A person the user chooses to associate with Conversations and relationship context.
_Avoid_: Account, target, chat partner

**Relationship Memory**:
User-owned, editable observations associated with a Contact, such as communication style, recurring topics, emotional patterns, and important events.
_Avoid_: Profile inference, permanent truth, surveillance record

**Memory Observation**:
A single editable piece of Relationship Memory with provenance and a confidence/updated timestamp.
_Avoid_: Fact unless the user explicitly confirmed it

## Assistant Surface

**Assistant Surface**:
A UI location where Jev presents an Analysis or Reply Plan, such as the main app or an explicitly enabled floating assistant.
_Avoid_: Agent, autonomous assistant

**Conversation Source**:
The controlled adapter that supplies a Conversation to the analysis flow.
_Avoid_: Hook when referring to supported product behavior

