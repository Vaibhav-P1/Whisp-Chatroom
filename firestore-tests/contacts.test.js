const { before, after, beforeEach, describe, it } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require('@firebase/rules-unit-testing');
const {
  doc, getDoc, getDocs, collection, setDoc, updateDoc, deleteDoc, writeBatch,
  serverTimestamp,
} = require('firebase/firestore');

// Phase 5B (part 1): handles, friend requests and friendships.
let env;
const asUser = (uid) => env.authenticatedContext(uid).firestore();
const anon = () => env.unauthenticatedContext().firestore();

const profile = { firstName: 'Ann', lastName: 'Lee', email: 'a@b.com' };

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'wishp-rules-test-contacts',
    firestore: {
      rules: fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});

after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

async function seedUser(uid, extra = {}) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'users', uid), { ...profile, ...extra });
  });
}

// Claims a handle the way the app does: the handle doc and the profile field in one batch.
function claim(uid, handle, { releasing } = {}) {
  const db = asUser(uid);
  const batch = writeBatch(db);
  batch.set(doc(db, 'handles', handle), { uid, displayName: 'Ann L', createdAt: serverTimestamp() });
  batch.update(doc(db, 'users', uid), { handle, handleChangedAt: serverTimestamp() });
  if (releasing) batch.delete(doc(db, 'handles', releasing));
  return batch.commit();
}

describe('handles', () => {
  it('claims a handle atomically with the profile', async () => {
    await seedUser('u1');
    await assertSucceeds(claim('u1', 'ann_lee'));
    await assertSucceeds(getDoc(doc(asUser('u2'), 'handles', 'ann_lee')));
  });

  it('never lets anyone list handles', async () => {
    await seedUser('u1');
    await claim('u1', 'ann_lee');
    await assertFails(getDocs(collection(asUser('u2'), 'handles')));
    await assertFails(getDoc(doc(anon(), 'handles', 'ann_lee')));
  });

  it('rejects malformed, short, trailing underscore and reserved handles', async () => {
    await seedUser('u1');
    for (const bad of ['Ann_Lee', 'ab', '1abc', 'ann_', 'admin', 'has space', 'a'.repeat(21)]) {
      await assertFails(claim('u1', bad));
    }
  });

  it('gives a handle to only one user, even when both claim it', async () => {
    await seedUser('u1');
    await seedUser('u2');
    await assertSucceeds(claim('u1', 'taken_name'));
    await assertFails(claim('u2', 'taken_name'));
  });

  it('rejects a handle doc for someone else, or without the profile field', async () => {
    await seedUser('u1');
    await seedUser('u2');
    const db = asUser('u1');
    await assertFails(setDoc(doc(db, 'handles', 'solo_doc'), { uid: 'u1', displayName: 'Ann L', createdAt: serverTimestamp() }));
    const batch = writeBatch(db);
    batch.set(doc(db, 'handles', 'fake_owner'), { uid: 'u2', displayName: 'Bob', createdAt: serverTimestamp() });
    batch.update(doc(db, 'users', 'u1'), { handle: 'fake_owner', handleChangedAt: serverTimestamp() });
    await assertFails(batch.commit());
  });

  it('rejects setting a handle on the profile without owning the handle doc', async () => {
    await seedUser('u1');
    await seedUser('u2');
    await claim('u2', 'bob_one');
    await assertFails(updateDoc(doc(asUser('u1'), 'users', 'u1'), { handle: 'bob_one', handleChangedAt: serverTimestamp() }));
  });

  it('blocks a second rename within 30 days but allows one after', async () => {
    await seedUser('u1');
    await assertSucceeds(claim('u1', 'first_name'));
    await assertFails(claim('u1', 'second_name', { releasing: 'first_name' }));

    const old = new Date(Date.now() - 31 * 24 * 3600 * 1000);
    await env.withSecurityRulesDisabled(async (ctx) => {
      await updateDoc(doc(ctx.firestore(), 'users', 'u1'), { handleChangedAt: old });
    });
    await assertSucceeds(claim('u1', 'second_name', { releasing: 'first_name' }));
  });

  it('requires the old handle to be released when renaming', async () => {
    await seedUser('u1');
    await claim('u1', 'first_name');
    const old = new Date(Date.now() - 31 * 24 * 3600 * 1000);
    await env.withSecurityRulesDisabled(async (ctx) => {
      await updateDoc(doc(ctx.firestore(), 'users', 'u1'), { handleChangedAt: old });
    });
    await assertFails(claim('u1', 'second_name'));
  });

  it('does not let a user fake handleChangedAt while editing other fields', async () => {
    await seedUser('u1');
    await claim('u1', 'first_name');
    await assertFails(updateDoc(doc(asUser('u1'), 'users', 'u1'), { handleChangedAt: new Date(0) }));
    await assertSucceeds(updateDoc(doc(asUser('u1'), 'users', 'u1'), { firstName: 'Anna' }));
  });

  it('lets only the owner release a handle', async () => {
    await seedUser('u1');
    await claim('u1', 'ann_lee');
    await assertFails(deleteDoc(doc(asUser('u2'), 'handles', 'ann_lee')));
    await assertSucceeds(deleteDoc(doc(asUser('u1'), 'handles', 'ann_lee')));
  });
});

describe('friend requests and friendships', () => {
  beforeEach(async () => {
    await seedUser('a', { handle: 'alice_a' });
    await seedUser('b', { handle: 'bob_bee' });
  });

  const request = (from, to, over = {}) => ({
    from, to, fromHandle: from === 'a' ? 'alice_a' : 'bob_bee', fromName: 'Name',
    createdAt: serverTimestamp(), ...over,
  });
  const reqRef = (db, from, to) => doc(db, 'friendRequests', `${from}_${to}`);

  it('sends a request as yourself, readable by both sides only', async () => {
    await assertSucceeds(setDoc(reqRef(asUser('a'), 'a', 'b'), request('a', 'b')));
    await assertSucceeds(getDoc(reqRef(asUser('a'), 'a', 'b')));
    await assertSucceeds(getDoc(reqRef(asUser('b'), 'a', 'b')));
    await assertFails(getDoc(reqRef(asUser('c'), 'a', 'b')));
  });

  it('rejects spoofed senders, wrong ids, self requests, fake handles and extra fields', async () => {
    await assertFails(setDoc(reqRef(asUser('b'), 'a', 'b'), request('a', 'b')));
    await assertFails(setDoc(doc(asUser('a'), 'friendRequests', 'wrong'), request('a', 'b')));
    await assertFails(setDoc(reqRef(asUser('a'), 'a', 'a'), request('a', 'a')));
    await assertFails(setDoc(reqRef(asUser('a'), 'a', 'b'), request('a', 'b', { fromHandle: 'bob_bee' })));
    await assertFails(setDoc(reqRef(asUser('a'), 'a', 'b'), request('a', 'b', { extra: 1 })));
    await assertFails(setDoc(reqRef(asUser('a'), 'a', 'b'), request('a', 'b', { createdAt: new Date() })));
  });

  it('rejects a reverse request while one is pending', async () => {
    await assertSucceeds(setDoc(reqRef(asUser('a'), 'a', 'b'), request('a', 'b')));
    await assertFails(setDoc(reqRef(asUser('b'), 'b', 'a'), request('b', 'a')));
  });

  it('rejects a request when the target blocked the sender', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'users', 'b', 'blocks', 'a'), { createdAt: new Date() });
    });
    await assertFails(setDoc(reqRef(asUser('a'), 'a', 'b'), request('a', 'b')));
  });

  it('lets either side remove a request but nobody edit it', async () => {
    await setDoc(reqRef(asUser('a'), 'a', 'b'), request('a', 'b'));
    await assertFails(updateDoc(reqRef(asUser('a'), 'a', 'b'), { fromName: 'x' }));
    await assertFails(deleteDoc(reqRef(asUser('c'), 'a', 'b')));
    await assertSucceeds(deleteDoc(reqRef(asUser('b'), 'a', 'b')));
  });

  function accept() {
    const db = asUser('b');
    const batch = writeBatch(db);
    batch.set(doc(db, 'users', 'b', 'friends', 'a'), { since: serverTimestamp(), handle: 'alice_a' });
    batch.set(doc(db, 'users', 'a', 'friends', 'b'), { since: serverTimestamp(), handle: 'bob_bee' });
    batch.delete(reqRef(db, 'a', 'b'));
    return batch.commit();
  }

  it('accepts a request by writing both friend docs and consuming the request', async () => {
    await setDoc(reqRef(asUser('a'), 'a', 'b'), request('a', 'b'));
    await assertSucceeds(accept());
    await assertSucceeds(getDoc(doc(asUser('a'), 'users', 'a', 'friends', 'b')));
    await assertSucceeds(getDoc(doc(asUser('b'), 'users', 'b', 'friends', 'a')));
    await assertFails(getDoc(doc(asUser('b'), 'users', 'a', 'friends', 'b')));
  });

  it('rejects friend docs without a pending request, or without consuming it', async () => {
    const db = asUser('b');
    const none = writeBatch(db);
    none.set(doc(db, 'users', 'b', 'friends', 'a'), { since: serverTimestamp(), handle: 'alice_a' });
    await assertFails(none.commit());

    await setDoc(reqRef(asUser('a'), 'a', 'b'), request('a', 'b'));
    const keep = writeBatch(db);
    keep.set(doc(db, 'users', 'b', 'friends', 'a'), { since: serverTimestamp(), handle: 'alice_a' });
    await assertFails(keep.commit());
  });

  it('does not let the sender accept their own request', async () => {
    await setDoc(reqRef(asUser('a'), 'a', 'b'), request('a', 'b'));
    const db = asUser('a');
    const batch = writeBatch(db);
    batch.set(doc(db, 'users', 'a', 'friends', 'b'), { since: serverTimestamp(), handle: 'bob_bee' });
    batch.set(doc(db, 'users', 'b', 'friends', 'a'), { since: serverTimestamp(), handle: 'alice_a' });
    batch.delete(reqRef(db, 'a', 'b'));
    await assertFails(batch.commit());
  });

  it('lets either side remove the friendship, and blocks a new request while friends', async () => {
    await setDoc(reqRef(asUser('a'), 'a', 'b'), request('a', 'b'));
    await accept();
    await assertFails(setDoc(reqRef(asUser('a'), 'a', 'b'), request('a', 'b')));
    await assertFails(deleteDoc(doc(asUser('c'), 'users', 'a', 'friends', 'b')));
    await assertSucceeds(deleteDoc(doc(asUser('b'), 'users', 'a', 'friends', 'b')));
    await assertSucceeds(deleteDoc(doc(asUser('b'), 'users', 'b', 'friends', 'a')));
  });
});

describe('account deletion with contacts', () => {
  it('lets a user delete their profile, handle, friendship, prefs and requests in one batch', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      await setDoc(doc(db, 'users', 'a'), { ...profile, handle: 'alice_a' });
      await setDoc(doc(db, 'users', 'b'), { ...profile, handle: 'bob_bee' });
      await setDoc(doc(db, 'handles', 'alice_a'), { uid: 'a', displayName: 'Ann L', createdAt: new Date() });
      await setDoc(doc(db, 'users', 'a', 'friends', 'b'), { since: new Date(), handle: 'bob_bee' });
      await setDoc(doc(db, 'users', 'b', 'friends', 'a'), { since: new Date(), handle: 'alice_a' });
      await setDoc(doc(db, 'users', 'a', 'chats', 'dm_a_b'), { pinned: true });
      await setDoc(doc(db, 'users', 'a', 'blocks', 'c'), { createdAt: new Date() });
      await setDoc(doc(db, 'friendRequests', 'c_a'), {
        from: 'c', to: 'a', fromHandle: 'cy_cy', fromName: 'Cy', createdAt: new Date(),
      });
    });
    const db = asUser('a');
    const batch = writeBatch(db);
    batch.delete(doc(db, 'users', 'a', 'friends', 'b'));
    batch.delete(doc(db, 'users', 'b', 'friends', 'a'));
    batch.delete(doc(db, 'users', 'a', 'chats', 'dm_a_b'));
    batch.delete(doc(db, 'users', 'a', 'blocks', 'c'));
    batch.delete(doc(db, 'friendRequests', 'c_a'));
    batch.delete(doc(db, 'handles', 'alice_a'));
    batch.delete(doc(db, 'users', 'a'));
    await assertSucceeds(batch.commit());
  });
});
