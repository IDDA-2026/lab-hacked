# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

Before attacking anything, write down what the app does and where untrusted input
reaches the backend. Which inputs does a stranger control?

InkFeed is a small community application where users can view drawings, search the feed, and leave comments without needing an account. 

A completely unauthenticated stranger controls three entry points:
1. The search query parameter `q` via `GET /api/posts/search?q=...`
2. The comment author name via `POST /api/posts/{postId}/comments`
3. The comment body via `POST /api/posts/{postId}/comments`

The most critical entry point is the search box, as this input is passed directly to the backend database layer to filter posts.

---

## 2. Reproducing the breach

### What I've typed to test the vulnerability and where

I typed the following payload directly into the search box on the frontend (`http://localhost:3000`):

```sql
' UNION SELECT id, email, password FROM users --