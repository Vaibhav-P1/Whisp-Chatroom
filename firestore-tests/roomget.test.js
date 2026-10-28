const { before, after, beforeEach, describe, it } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require('@firebase/rules-unit-testing');
const { doc, getDoc, setDoc, serverTimestamp } = require('firebase/firestore');

// Regression: the app reads a room that does not exist yet (to avoid overwriting a code collision, and
// to tell a user that a code is wrong) before it creates or joins it. That read must be allowed.
let env;
const asUser = (uid) => env.authenticatedContext(uid).firestore();
const anon = () => env.unauthenticatedContext().firestore();

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'wishp-rules-test-roomget',
    firestore: {
      rules: fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});

after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

describe('reading a room that does not exist', () => {
  it('is allowed for a signed-in user, and reports that nothing is there', async () => {
    const snap = await assertSucceeds(getDoc(doc(asUser('u1'), 'rooms', 'NOROOM')));
    if (snap.exists()) throw new Error('should not exist');
  });

  it('is still denied when signed out', async () => {
    await assertFails(getDoc(doc(anon(), 'rooms', 'NOROOM')));
  });

  it('does not make DMs readable by strangers', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'rooms', 'dm_a_b'), {
        roomCode: 'dm_a_b', creatorUid: 'a', roomOpen: true, members: { a: 'A', b: 'B' },
        temporary: false, timestamp: new Date(), type: 'dm', locked: false,
      });
    });
    await assertFails(getDoc(doc(asUser('c'), 'rooms', 'dm_a_b')));
    await assertSucceeds(getDoc(doc(asUser('a'), 'rooms', 'dm_a_b')));
  });

  it('lets the create flow read first, then create the room', async () => {
    const db = asUser('owner');
    const ref = doc(db, 'rooms', 'ABC123');
    const before = await assertSucceeds(getDoc(ref));
    if (before.exists()) throw new Error('should not exist');
    await assertSucceeds(setDoc(ref, {
      roomCode: 'ABC123', creatorUid: 'owner', roomOpen: true, members: { owner: 'Owner' },
      temporary: false, timestamp: serverTimestamp(),
      mission: { title: 'Demo', goal: '', durationSeconds: 1800 },
    }));
  });

  it('keeps a long-gone mission unreadable', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'rooms', 'OLD123'), {
        roomCode: 'OLD123', creatorUid: 'owner', roomOpen: true, members: { owner: 'Owner' },
        temporary: false, timestamp: new Date(Date.now() - 3 * 24 * 3600 * 1000),
        mission: { title: 'Old', goal: '', durationSeconds: 1800 },
      });
    });
    await assertFails(getDoc(doc(asUser('owner'), 'rooms', 'OLD123')));
  });
});
