const { before, after, beforeEach, describe, it } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require('@firebase/rules-unit-testing');
const {
  doc, getDoc, getDocs, collection, setDoc, updateDoc, deleteDoc, addDoc, writeBatch,
  serverTimestamp, deleteField,
} = require('firebase/firestore');

// Phase 5B (part 2): direct messages, locking, previews and inbox preferences.
let env;
const asUser = (uid) => env.authenticatedContext(uid).firestore();

const DM = 'dm_a_b';
const profile = { firstName: 'Ann', lastName: 'Lee', email: 'a@b.com' };

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'wishp-rules-test-dm',
    firestore: {
      rules: fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});

after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

const dmData = (over = {}) => ({
  roomCode: DM, creatorUid: 'b', roomOpen: true, members: { a: 'Ann', b: 'Bob' },
  temporary: false, timestamp: serverTimestamp(), type: 'dm', locked: false, ...over,
});

async function seedFriends() {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'users', 'a'), { ...profile, handle: 'alice_a' });
    await setDoc(doc(db, 'users', 'b'), { ...profile, handle: 'bob_bee' });
    await setDoc(doc(db, 'friendRequests', 'a_b'), {
      from: 'a', to: 'b', fromHandle: 'alice_a', fromName: 'Ann', createdAt: new Date(),
    });
  });
}

// What the app's "accept" does: both friend docs, the DM and the consumed request in one batch.
function acceptBatch({ dm = dmData(), uid = 'b' } = {}) {
  const db = asUser(uid);
  const batch = writeBatch(db);
  batch.set(doc(db, 'users', 'b', 'friends', 'a'), { since: serverTimestamp(), handle: 'alice_a' });
  batch.set(doc(db, 'users', 'a', 'friends', 'b'), { since: serverTimestamp(), handle: 'bob_bee' });
  if (dm) batch.set(doc(db, 'rooms', DM), dm);
  batch.delete(doc(db, 'friendRequests', 'a_b'));
  return batch;
}

async function seedDm({ locked = false, lockedBy } = {}) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'rooms', DM), {
      roomCode: DM, creatorUid: 'b', roomOpen: true, members: { a: 'Ann', b: 'Bob' },
      temporary: false, timestamp: new Date(), type: 'dm', locked,
      ...(lockedBy ? { lockedBy } : {}),
    });
    await setDoc(doc(db, 'users', 'a', 'friends', 'b'), { since: new Date(), handle: 'bob_bee' });
    await setDoc(doc(db, 'users', 'b', 'friends', 'a'), { since: new Date(), handle: 'alice_a' });
  });
}

describe('creating a DM', () => {
  it('is created by the accepting batch, with a deterministic id', async () => {
    await seedFriends();
    await assertSucceeds(acceptBatch().commit());
    await assertSucceeds(getDoc(doc(asUser('a'), 'rooms', DM)));
  });

  it('rejects a DM between people who are not friends', async () => {
    await assertFails(setDoc(doc(asUser('b'), 'rooms', DM), dmData()));
  });

  it('rejects the reversed id, a third member, extra fields and a wrong creator', async () => {
    await seedFriends();
    await assertFails(acceptBatch({ dm: dmData({ roomCode: 'dm_b_a' }) }).commit());
    await assertFails(acceptBatch({ dm: dmData({ members: { a: 'Ann', b: 'Bob', c: 'Cy' } }) }).commit());
    await assertFails(acceptBatch({ dm: dmData({ admin: true }) }).commit());
    await assertFails(acceptBatch({ dm: dmData({ creatorUid: 'a' }) }).commit());
    await assertFails(acceptBatch({ dm: dmData({ type: 'code' }) }).commit());
  });

  it('cannot be created a second time', async () => {
    await seedDm();
    await assertFails(setDoc(doc(asUser('b'), 'rooms', DM), dmData()));
  });
});

describe('DM access', () => {
  it('is readable by its two members only, never listed or joinable', async () => {
    await seedDm();
    await assertSucceeds(getDoc(doc(asUser('a'), 'rooms', DM)));
    await assertSucceeds(getDoc(doc(asUser('b'), 'rooms', DM)));
    await assertFails(getDoc(doc(asUser('c'), 'rooms', DM)));
    await assertFails(getDocs(collection(asUser('c'), 'rooms')));
    await assertFails(updateDoc(doc(asUser('c'), 'rooms', DM), { 'members.c': 'Cy' }));
  });

  it('cannot be closed, deleted or left through the members map', async () => {
    await seedDm();
    await assertFails(updateDoc(doc(asUser('b'), 'rooms', DM), { roomOpen: false }));
    await assertFails(deleteDoc(doc(asUser('b'), 'rooms', DM)));
    await assertFails(updateDoc(doc(asUser('a'), 'rooms', DM), { 'members.a': deleteField() }));
  });

  it('keeps code rooms readable by code', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'rooms', 'ABC123'), {
        roomCode: 'ABC123', creatorUid: 'a', roomOpen: true, members: { a: 'Ann' },
        temporary: false, timestamp: new Date(),
      });
    });
    await assertSucceeds(getDoc(doc(asUser('c'), 'rooms', 'ABC123')));
  });

  it('lets only members chat in a DM', async () => {
    await seedDm();
    const msg = (uid, name) => ({ uid, username: name, messageText: 'hi', timestamp: serverTimestamp() });
    await assertSucceeds(addDoc(collection(asUser('a'), 'rooms', DM, 'messages'), msg('a', 'Ann')));
    await assertFails(addDoc(collection(asUser('c'), 'rooms', DM, 'messages'), msg('c', 'Cy')));
  });
});

describe('locking a DM', () => {
  const lock = (uid) => updateDoc(doc(asUser(uid), 'rooms', DM), { locked: true, lockedBy: uid });
  const unlock = (uid) => updateDoc(doc(asUser(uid), 'rooms', DM), { locked: false, lockedBy: '' });
  const msg = { uid: 'a', username: 'Ann', messageText: 'hi', timestamp: serverTimestamp() };

  it('lets either member lock it, and a locked DM rejects new messages', async () => {
    await seedDm();
    await assertSucceeds(lock('b'));
    await assertFails(addDoc(collection(asUser('a'), 'rooms', DM, 'messages'), msg));
    await assertSucceeds(getDoc(doc(asUser('a'), 'rooms', DM)));
  });

  it('does not let a stranger lock it, or a member lock it in someone elses name', async () => {
    await seedDm();
    await assertFails(lock('c'));
    await assertFails(updateDoc(doc(asUser('a'), 'rooms', DM), { locked: true, lockedBy: 'b' }));
  });

  it('lets only the locker unlock it while the friendship is gone', async () => {
    await seedDm({ locked: true, lockedBy: 'b' });
    await env.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await deleteDoc(doc(db, 'users', 'a', 'friends', 'b'));
      await deleteDoc(doc(db, 'users', 'b', 'friends', 'a'));
    });
    await assertFails(unlock('a'));
    await assertSucceeds(unlock('b'));
  });

  it('unlocks when the two become friends again', async () => {
    await seedDm({ locked: true, lockedBy: 'b' });
    await assertSucceeds(unlock('a'));
    await assertSucceeds(addDoc(collection(asUser('a'), 'rooms', DM, 'messages'), msg));
  });
});

describe('conversation preview', () => {
  const preview = (over = {}) => ({
    lastMessage: { text: 'hello', uid: 'a', at: serverTimestamp() },
    lastMessageAt: serverTimestamp(), ...over,
  });

  it('is updated by a member together with their message', async () => {
    await seedDm();
    const db = asUser('a');
    const batch = writeBatch(db);
    batch.set(doc(db, 'rooms', DM, 'messages', 'm1'), {
      uid: 'a', username: 'Ann', messageText: 'hello', timestamp: serverTimestamp(),
    });
    batch.update(doc(db, 'rooms', DM), preview());
    await assertSucceeds(batch.commit());
  });

  it('rejects strangers, spoofed senders, long text, client times and extra fields', async () => {
    await seedDm();
    const ref = (uid) => doc(asUser(uid), 'rooms', DM);
    await assertFails(updateDoc(ref('c'), preview()));
    await assertFails(updateDoc(ref('a'), preview({ lastMessage: { text: 'x', uid: 'b', at: serverTimestamp() } })));
    await assertFails(updateDoc(ref('a'), preview({ lastMessage: { text: 'x'.repeat(101), uid: 'a', at: serverTimestamp() } })));
    await assertFails(updateDoc(ref('a'), preview({ lastMessageAt: new Date() })));
    await assertFails(updateDoc(ref('a'), { ...preview(), roomOpen: false }));
  });
});

describe('inbox preferences', () => {
  const prefs = { pinned: true, muted: false, archived: false, lastReadAt: new Date() };

  it('are private to their owner', async () => {
    const mine = doc(asUser('a'), 'users', 'a', 'chats', DM);
    await assertSucceeds(setDoc(mine, prefs));
    await assertSucceeds(getDoc(mine));
    await assertFails(getDoc(doc(asUser('b'), 'users', 'a', 'chats', DM)));
    await assertFails(setDoc(doc(asUser('b'), 'users', 'a', 'chats', DM), prefs));
    await assertFails(setDoc(mine, { ...prefs, admin: true }));
    await assertFails(setDoc(mine, { ...prefs, pinned: 'yes' }));
    await assertSucceeds(deleteDoc(mine));
  });
});
