const { before, after, beforeEach, describe, it } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require('@firebase/rules-unit-testing');
const {
  doc, getDoc, collection, setDoc, updateDoc, deleteDoc, addDoc,
  serverTimestamp, deleteField,
} = require('firebase/firestore');

// Phase 5A: account deletion, blocks, reports, kick/ban and the moderation log.
const ROOM = 'ABC123';
let env;

const asUser = (uid) => env.authenticatedContext(uid).firestore();
const anon = () => env.unauthenticatedContext().firestore();

async function seedRoom() {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'rooms', ROOM), {
      roomCode: ROOM,
      creatorUid: 'owner',
      roomOpen: true,
      members: { owner: 'Owner', alice: 'Alice' },
      temporary: false,
      timestamp: new Date(),
    });
    await setDoc(doc(db, 'rooms', ROOM, 'messages', 'm1'), {
      uid: 'alice', username: 'Alice', messageText: 'hi', timestamp: new Date(),
    });
  });
}

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'wishp-rules-test-moderation',
    firestore: {
      rules: fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});

after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

describe('account deletion and blocks', () => {
  const profile = { firstName: 'Ann', lastName: 'Lee', email: 'a@b.com' };

  it('lets only the owner delete their profile', async () => {
    await assertSucceeds(setDoc(doc(asUser('u1'), 'users', 'u1'), profile));
    await assertFails(deleteDoc(doc(asUser('u2'), 'users', 'u1')));
    await assertFails(deleteDoc(doc(anon(), 'users', 'u1')));
    await assertSucceeds(deleteDoc(doc(asUser('u1'), 'users', 'u1')));
  });

  it('keeps the block list private to its owner', async () => {
    const mine = doc(asUser('u1'), 'users', 'u1', 'blocks', 'u2');
    await assertSucceeds(setDoc(mine, { createdAt: serverTimestamp() }));
    await assertSucceeds(getDoc(mine));
    await assertFails(getDoc(doc(asUser('u2'), 'users', 'u1', 'blocks', 'u2')));
    await assertFails(setDoc(doc(asUser('u2'), 'users', 'u1', 'blocks', 'u3'), { createdAt: serverTimestamp() }));
    await assertFails(deleteDoc(doc(asUser('u2'), 'users', 'u1', 'blocks', 'u2')));
    await assertSucceeds(deleteDoc(mine));
  });

  it('rejects blocking yourself, client timestamps and extra fields', async () => {
    const db = asUser('u1');
    await assertFails(setDoc(doc(db, 'users', 'u1', 'blocks', 'u1'), { createdAt: serverTimestamp() }));
    await assertFails(setDoc(doc(db, 'users', 'u1', 'blocks', 'u2'), { createdAt: new Date() }));
    await assertFails(setDoc(doc(db, 'users', 'u1', 'blocks', 'u2'), { createdAt: serverTimestamp(), note: 'x' }));
  });
});

describe('reports', () => {
  const report = (over = {}) => ({
    reporterUid: 'alice', targetUid: 'owner', roomId: ROOM, messageId: 'm1', category: 'spam',
    description: 'bad', snapshot: 'hi', createdAt: serverTimestamp(), ...over,
  });
  const reports = (uid) => collection(asUser(uid), 'reports');

  it('lets a room member report with a valid category', async () => {
    await seedRoom();
    await assertSucceeds(addDoc(reports('alice'), report()));
  });

  it('accepts a minimal report with no room or message', async () => {
    const minimal = {
      reporterUid: 'alice', targetUid: 'owner', category: 'harassment', createdAt: serverTimestamp(),
    };
    await assertSucceeds(addDoc(reports('alice'), minimal));
  });

  it('rejects bad categories, spoofed reporters, self reports, long text and extra fields', async () => {
    await seedRoom();
    await assertFails(addDoc(reports('alice'), report({ category: 'nope' })));
    await assertFails(addDoc(reports('alice'), report({ reporterUid: 'owner' })));
    await assertFails(addDoc(reports('alice'), report({ targetUid: 'alice' })));
    await assertFails(addDoc(reports('alice'), report({ description: 'x'.repeat(501) })));
    await assertFails(addDoc(reports('alice'), report({ snapshot: 'x'.repeat(501) })));
    await assertFails(addDoc(reports('alice'), report({ createdAt: new Date() })));
    await assertFails(addDoc(reports('alice'), report({ status: 'closed' })));
    await assertFails(addDoc(collection(anon(), 'reports'), report()));
  });

  it('requires room membership for room-scoped reports', async () => {
    await seedRoom();
    await assertFails(addDoc(reports('stranger'), report({ reporterUid: 'stranger' })));
  });

  it('lets only the reporter read their own report, and nobody edit or delete it', async () => {
    await seedRoom();
    const ref = await addDoc(reports('alice'), report());
    await assertSucceeds(getDoc(doc(asUser('alice'), 'reports', ref.id)));
    await assertFails(getDoc(doc(asUser('owner'), 'reports', ref.id)));
    await assertFails(getDoc(doc(anon(), 'reports', ref.id)));
    await assertFails(updateDoc(doc(asUser('alice'), 'reports', ref.id), { category: 'other' }));
    await assertFails(deleteDoc(doc(asUser('alice'), 'reports', ref.id)));
  });
});

describe('kick, ban and modlog', () => {
  const room = () => doc(asUser('owner'), 'rooms', ROOM);
  const ban = (uid) => doc(asUser('owner'), 'rooms', ROOM, 'bans', uid);

  it('lets the owner kick another member', async () => {
    await seedRoom();
    await assertSucceeds(updateDoc(room(), { 'members.alice': deleteField() }));
  });

  it('does not let the owner kick themselves, or a member kick someone else', async () => {
    await seedRoom();
    await assertFails(updateDoc(room(), { 'members.owner': deleteField() }));
    await assertFails(updateDoc(doc(asUser('alice'), 'rooms', ROOM), { 'members.owner': deleteField() }));
  });

  it('does not let the owner kick several members in one write', async () => {
    await seedRoom();
    await env.withSecurityRulesDisabled(async (ctx) => {
      await updateDoc(doc(ctx.firestore(), 'rooms', ROOM), { 'members.bob': 'Bob' });
    });
    await assertFails(updateDoc(room(), { 'members.alice': deleteField(), 'members.bob': deleteField() }));
  });

  it('lets only the owner manage bans', async () => {
    await seedRoom();
    const data = { username: 'Alice', bannedAt: serverTimestamp(), reason: 'spam' };
    await assertFails(setDoc(doc(asUser('alice'), 'rooms', ROOM, 'bans', 'alice'), data));
    await assertFails(setDoc(ban('owner'), data));
    await assertFails(setDoc(ban('alice'), { ...data, reason: 'x'.repeat(101) }));
    await assertFails(setDoc(ban('alice'), { ...data, bannedAt: new Date() }));
    await assertSucceeds(setDoc(ban('alice'), data));
    await assertFails(getDoc(doc(asUser('alice'), 'rooms', ROOM, 'bans', 'alice')));
    await assertSucceeds(getDoc(ban('alice')));
    await assertFails(deleteDoc(doc(asUser('alice'), 'rooms', ROOM, 'bans', 'alice')));
    await assertSucceeds(deleteDoc(ban('alice')));
  });

  it('stops a banned user from rejoining until they are unbanned', async () => {
    await seedRoom();
    await assertSucceeds(updateDoc(room(), { 'members.alice': deleteField() }));
    await assertSucceeds(setDoc(ban('alice'), { username: 'Alice', bannedAt: serverTimestamp(), reason: '' }));
    await assertFails(updateDoc(doc(asUser('alice'), 'rooms', ROOM), { 'members.alice': 'Alice' }));
    await assertSucceeds(updateDoc(doc(asUser('bob'), 'rooms', ROOM), { 'members.bob': 'Bob' }));
    await assertSucceeds(deleteDoc(ban('alice')));
    await assertSucceeds(updateDoc(doc(asUser('alice'), 'rooms', ROOM), { 'members.alice': 'Alice' }));
  });

  it('keeps an append-only modlog visible to the owner only', async () => {
    await seedRoom();
    const entry = { action: 'kick', targetUid: 'alice', actorUid: 'owner', at: serverTimestamp() };
    const log = (uid) => collection(asUser(uid), 'rooms', ROOM, 'modlog');
    await assertFails(addDoc(log('alice'), { ...entry, actorUid: 'alice' }));
    await assertFails(addDoc(log('owner'), { ...entry, action: 'delete-everything' }));
    await assertFails(addDoc(log('owner'), { ...entry, at: new Date() }));
    const ref = await addDoc(log('owner'), entry);
    await assertSucceeds(getDoc(doc(asUser('owner'), 'rooms', ROOM, 'modlog', ref.id)));
    await assertFails(getDoc(doc(asUser('alice'), 'rooms', ROOM, 'modlog', ref.id)));
    await assertFails(updateDoc(doc(asUser('owner'), 'rooms', ROOM, 'modlog', ref.id), { action: 'ban' }));
    await assertFails(deleteDoc(doc(asUser('owner'), 'rooms', ROOM, 'modlog', ref.id)));
  });
});
