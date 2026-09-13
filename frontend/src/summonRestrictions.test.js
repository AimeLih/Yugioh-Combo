import test from 'node:test'
import assert from 'node:assert/strict'
import {
  activationSummonRestrictions,
  restrictionAllowsCard,
  summonRestrictionReason,
} from './summonRestrictions.js'

const trudea = {
  id: 1,
  name: 'Centur-Ion Trudea',
  type: 'Effect Monster',
  archetype: 'Centur-Ion',
  description: 'During your Main Phase: You can place this card you control and 1 "Centur-Ion" monster from your hand or Deck, except "Centur-Ion Trudea", in your Spell & Trap Zones as face-up Continuous Traps, also you cannot Special Summon "Centur-Ion Trudea" for the rest of this turn.',
}

test('Trudea locks only Trudea rather than the entire Centur-Ion archetype', () => {
  const restrictions = activationSummonRestrictions(trudea, trudea.description)
  const atrii = { name: 'Centur-Ion Atrii', type: 'Tuner Monster', archetype: 'Centur-Ion' }

  assert.equal(restrictions.length, 1)
  assert.equal(restrictions[0].allowed, '')
  assert.equal(restrictions[0].prohibited, '"Centur-Ion Trudea"')
  assert.equal(summonRestrictionReason(restrictions, atrii, false), null)
  assert.equal(summonRestrictionReason(restrictions, trudea, false, false), null)
  assert.match(summonRestrictionReason(restrictions, trudea, false, true), /Centur-Ion Trudea/)
})

test('extra deck exception continues to allow only the named monster type', () => {
  const brandedFusion = {
    id: 2,
    name: 'Branded Fusion',
    description: 'You cannot Special Summon from the Extra Deck, except Fusion Monsters, the turn you activate this card.',
  }
  const [restriction] = activationSummonRestrictions(brandedFusion, brandedFusion.description)

  assert.equal(restrictionAllowsCard(restriction, { name: 'Albion', type: 'Fusion Monster' }, true), true)
  assert.equal(restrictionAllowsCard(restriction, { name: 'Baronne', type: 'Synchro Monster' }, true), false)
  assert.equal(restrictionAllowsCard(restriction, { name: 'Ash Blossom', type: 'Tuner Monster' }, false), true)
})

test('quoted archetype wording remains a family match', () => {
  const restriction = {
    scope: 'all',
    allowed: '"Centur-Ion" monsters',
    prohibited: '',
  }

  assert.equal(restrictionAllowsCard(restriction, {
    name: 'Centur-Ion Atrii',
    type: 'Tuner Monster',
    archetype: 'Centur-Ion',
  }, false), true)
  assert.equal(restrictionAllowsCard(restriction, {
    name: 'Ash Blossom & Joyous Spring',
    type: 'Tuner Monster',
    archetype: '',
  }, false), false)
})

test('a broad restriction without an exception stays conservatively enforced', () => {
  const card = {
    id: 3,
    name: 'Test Card',
    description: 'For the rest of this turn, you cannot Special Summon from the Extra Deck.',
  }
  const [restriction] = activationSummonRestrictions(card, card.description)

  assert.equal(restrictionAllowsCard(restriction, { name: 'Albion', type: 'Fusion Monster' }, true), false)
  assert.equal(restrictionAllowsCard(restriction, { name: 'Ash Blossom', type: 'Tuner Monster' }, false), true)
})
