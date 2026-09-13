import { useEffect, useRef, useState } from 'react'
import './App.css'
import { activationSummonRestrictions, summonRestrictionReason } from './summonRestrictions.js'

function typeColor(type) {
  if (!type) return '#1a3a5c'
  const t = type.toLowerCase()
  if (t.includes('spell')) return '#1d7a4b'
  if (t.includes('trap')) return '#7a1d6b'
  if (t.includes('fusion')) return '#8b3db5'
  if (t.includes('synchro')) return '#4a4a5a'
  if (t.includes('xyz')) return '#1a1a2a'
  if (t.includes('link')) return '#0b3d8c'
  if (t.includes('ritual')) return '#2a4fa0'
  if (t.includes('pendulum')) return '#2a7a6a'
  if (t.includes('normal')) return '#8b7510'
  return '#8b4a00'
}

function Stars({ count }) {
  if (!count) return null
  return <span className="level-stars">{'★'.repeat(Math.min(count, 12))}</span>
}

function WikiRow({ label, children }) {
  if (children === null || children === undefined || children === '') return null
  return (
      <div className="wiki-row">
        <span className="wiki-row-label">{label}</span>
        <span className="wiki-row-value">{children}</span>
      </div>
  )
}

function Badge({ label, color }) {
  return <span className="badge" style={{ background: color }}>{label}</span>
}

const ZONE_KEYS = [
  'monsterZone',
  'extraMonsterZone',
  'spellTrapZone',
  'pendulumZone',
  'hand',
  'graveyard',
  'banished',
  'extraDeck',
]

const ZONE_LABELS = {
  monsterZone: 'Main Monster Zone',
  extraMonsterZone: 'Extra Monster Zone',
  spellTrapZone: 'Spell & Trap Zone',
  pendulumZone: 'Pendulum Zone',
  hand: 'Hand',
  graveyard: 'Graveyard',
  banished: 'Banished',
  extraDeck: 'Face-up Extra Deck',
}

const ZONE_BUTTON_LABELS = {
  monsterZone: 'Main Monster',
  extraMonsterZone: 'Extra Monster',
  spellTrapZone: 'Spell & Trap',
  pendulumZone: 'Pendulum',
  hand: 'Hand',
  graveyard: 'Graveyard',
  banished: 'Banished',
  extraDeck: 'Extra Deck',
}

function emptyZones() {
  return {
    monsterZone: [],
    extraMonsterZone: [],
    spellTrapZone: [],
    pendulumZone: [],
    hand: [],
    graveyard: [],
    banished: [],
    extraDeck: [],
  }
}

function isTokenCard(card) {
  const type = (card.type || '').toLowerCase()
  return type.includes('token') || /\btoken\b/i.test(card.name || '')
}

function isMonsterCard(card) {
  return /monster/i.test(card.type || '') || isTokenCard(card)
}

function isPendulumMonster(card) {
  return isMonsterCard(card) && /pendulum/i.test(card.type || '')
}

function isExtraDeckMonster(card) {
  return isMonsterCard(card) && /(fusion|synchro|xyz|link)/i.test(card.type || '')
}

function isRitualMonster(card) {
  return isMonsterCard(card) && /ritual/i.test(card.type || '')
}

function manualPlacementZones(card) {
  if (isTokenCard(card)) return ['monsterZone', 'extraMonsterZone']

  if (isMonsterCard(card)) {
    const isExtraDeck = isExtraDeckMonster(card)
    const isRitual = isRitualMonster(card)
    const destinations = []

    if (!isExtraDeck || isRitual) destinations.push('hand')
    destinations.push('monsterZone')
    if (isExtraDeck || isRitual) destinations.push('extraMonsterZone')
    if (isPendulumMonster(card) && !isExtraDeck && !isRitual) destinations.push('pendulumZone', 'extraDeck')
    if (isExtraDeck) destinations.push('extraDeck')
    destinations.push('graveyard', 'banished')
    return destinations
  }

  if (/(spell|trap)/i.test(card.type || '')) {
    return ['hand', 'spellTrapZone', 'graveyard', 'banished']
  }

  return ['hand', 'graveyard', 'banished']
}

function placementCapacityReason(zones, destination) {
  if (destination === 'monsterZone' && zones.monsterZone.length >= 5) {
    return 'Main Monster Zones are full'
  }
  if (destination === 'extraMonsterZone' && zones.extraMonsterZone.length >= 1) {
    return 'Extra Monster Zone is occupied'
  }
  if (destination === 'pendulumZone') {
    if (zones.pendulumZone.length >= 2) return 'Pendulum Zones are full'
    if (zones.spellTrapZone.length + zones.pendulumZone.length >= 5) return 'Shared Spell & Trap slots are full'
  }
  if (destination === 'spellTrapZone' && zones.spellTrapZone.length >= 5 - zones.pendulumZone.length) {
    return 'Spell & Trap Zones are full'
  }
  return null
}

function pendulumScaleRange(zones) {
  if (zones.pendulumZone.length !== 2) return null
  const scales = zones.pendulumZone.map(entry => Number(entry.card.scale))
  if (scales.some(scale => !Number.isFinite(scale))) return null
  return { low: Math.min(...scales), high: Math.max(...scales) }
}

function linkedMainZoneCapacity(zones) {
  const normalizedMarkers = entry => (entry.card.linkmarkers || [])
      .map(marker => marker.toLowerCase().replace(/[_\s]/g, '-'))
  const fromExtraZone = zones.extraMonsterZone
      .filter(entry => extraDeckKind(entry.card) === 'Link')
      .flatMap(normalizedMarkers)
      .filter(marker => ['bottom-left', 'bottom', 'bottom-right'].includes(marker)).length
  const fromMainZones = zones.monsterZone
      .filter(entry => extraDeckKind(entry.card) === 'Link')
      .flatMap(normalizedMarkers)
      .filter(marker => ['left', 'right'].includes(marker)).length
  return Math.min(5 - zones.monsterZone.length, fromExtraZone + fromMainZones)
}

function allowedByPendulumScaleEffects(entry, zones) {
  return zones.pendulumZone.every(scaleEntry => {
    const pendulumText = normalizeEffectText(scaleEntry.card.description).split(/\[?\s*monster effect\s*\]?/i)[0]
    const restriction = pendulumText.match(/cannot pendulum summon monsters,? except ([^.]+)/i)?.[1]
    if (!restriction) return true
    const quotedFamilies = [...restriction.matchAll(/"([^"]+)"/g)].map(match => match[1].toLowerCase())
    if (quotedFamilies.length === 0) return true
    const name = (entry.card.name || '').toLowerCase()
    const archetype = (entry.card.archetype || '').toLowerCase()
    return quotedFamilies.some(family => name.includes(family) || archetype.includes(family))
  })
}

function canPendulumSummonFromFaceUpExtra(entry) {
  const type = (entry.card.type || '').toLowerCase()
  const isHybridExtraDeckMonster = /(fusion|synchro|xyz|link)/.test(type)
  return !isHybridExtraDeckMonster
      || /(?:can|must) be pendulum summoned|pendulum summon this face-up card/i.test(entry.card.description || '')
}

function hasBlockingSpecialSummonCondition(entry) {
  const description = (entry.card.description || '').toLowerCase()
  if (!description.includes('cannot be special summoned')) return false
  return !/can be pendulum summoned|must be pendulum summoned/.test(description)
}

function pendulumEligibleEntries(zones) {
  const range = pendulumScaleRange(zones)
  if (!range || range.low === range.high) return []
  const inRange = entry => {
    const level = Number(entry.card.level)
    return isMonsterCard(entry.card)
        && Number.isFinite(level)
        && level > range.low
        && level < range.high
        && !hasBlockingSpecialSummonCondition(entry)
        && allowedByPendulumScaleEffects(entry, zones)
  }
  return [
    ...zones.hand.filter(inRange).map(entry => ({ ...entry, pendulumSource: 'hand' })),
    ...zones.extraDeck
        .filter(entry => isPendulumMonster(entry.card) && canPendulumSummonFromFaceUpExtra(entry) && inRange(entry))
        .map(entry => ({ ...entry, pendulumSource: 'extraDeck' })),
  ]
}

function extraDeckKind(card) {
  const type = (card.type || '').toLowerCase()
  if (type.includes('synchro')) return 'Synchro'
  if (type.includes('xyz')) return 'Xyz'
  if (type.includes('link')) return 'Link'
  return null
}

function materialRequirement(card) {
  return normalizeEffectText(card.description)
      .split('\n')
      .map(line => line.trim())
      .find(line => /\bmonsters?\b/i.test(line) && /\d|tuner/i.test(line)) || 'Printed material requirements'
}

function materialClauses(requirement) {
  return requirement.split(/\s+\+\s+/).map(raw => {
    const countMatch = raw.match(/^\s*(\d+)(\+|\s+or more)?\s*/i)
    return {
      minimum: countMatch ? Number(countMatch[1]) : 1,
      repeatable: Boolean(countMatch?.[2]),
      descriptor: raw.replace(/^\s*\d+(?:\+|\s+or more)?\s*/i, '').trim(),
    }
  })
}

const MATERIAL_RACES = [
  'aqua', 'beast', 'beast-warrior', 'cyberse', 'dinosaur', 'divine-beast', 'dragon',
  'fairy', 'fiend', 'fish', 'illusion', 'insect', 'machine', 'plant', 'psychic',
  'pyro', 'reptile', 'rock', 'sea serpent', 'spellcaster', 'thunder', 'warrior',
  'winged beast', 'wyrm', 'zombie',
]
const MATERIAL_ATTRIBUTES = ['dark', 'divine', 'earth', 'fire', 'light', 'water', 'wind']

function matchesMaterialDescriptor(entry, descriptor) {
  const card = entry.card
  const rule = descriptor.toLowerCase()
  const type = (card.type || '').toLowerCase()
  const race = (card.race || '').toLowerCase()
  const attribute = (card.attribute || '').toLowerCase()
  const name = (card.name || '').toLowerCase()
  const archetype = (card.archetype || '').toLowerCase()

  if (rule.includes('non-tuner') && type.includes('tuner')) return false
  if (!rule.includes('non-tuner') && /\btuner\b/.test(rule) && !type.includes('tuner')) return false
  if (rule.includes('effect monster') && (!type.includes('effect') || isTokenCard(card))) return false
  if (rule.includes('normal monster') && !type.includes('normal') && !isTokenCard(card)) return false
  for (const kind of ['synchro', 'xyz', 'link', 'fusion', 'ritual', 'pendulum']) {
    if (rule.includes(`${kind} monster`) && !type.includes(kind)) return false
  }
  const requiredRaces = MATERIAL_RACES
      .filter(requiredRace => rule.includes(requiredRace))
      .filter(requiredRace => !MATERIAL_RACES.some(other =>
        other !== requiredRace && other.includes(requiredRace) && rule.includes(other)))
  if (requiredRaces.length > 0 && !requiredRaces.includes(race)) return false
  const requiredAttributes = MATERIAL_ATTRIBUTES.filter(requiredAttribute =>
    new RegExp(`\\b${requiredAttribute}\\b`).test(rule))
  if (requiredAttributes.length > 0 && !requiredAttributes.includes(attribute)) return false

  const quotedRequirements = [...descriptor.matchAll(/"([^"]+)"/g)].map(match => match[1].toLowerCase())
  if (quotedRequirements.length > 0
      && !quotedRequirements.some(required => name.includes(required) || archetype.includes(required))) {
    return false
  }
  return isMonsterCard(card)
}

function combinations(items, minimum = 2) {
  const results = []
  const visit = (start, chosen) => {
    if (chosen.length >= minimum) results.push([...chosen])
    if (chosen.length === Math.min(5, items.length)) return
    for (let index = start; index < items.length; index += 1) {
      chosen.push(items[index])
      visit(index + 1, chosen)
      chosen.pop()
    }
  }
  visit(0, [])
  return results
}

function satisfiesClauses(materials, clauses) {
  const requiredSlots = clauses.flatMap((clause, clauseIndex) =>
    Array.from({ length: clause.minimum }, () => clauseIndex))
  if (materials.length < requiredSlots.length) return false
  if (materials.length > requiredSlots.length && !clauses.some(clause => clause.repeatable)) return false

  const assigned = new Set()
  const assign = slotIndex => {
    if (slotIndex === requiredSlots.length) return true
    const clause = clauses[requiredSlots[slotIndex]]
    return materials.some((entry, materialIndex) => {
      if (assigned.has(materialIndex) || !matchesMaterialDescriptor(entry, clause.descriptor)) return false
      assigned.add(materialIndex)
      const valid = assign(slotIndex + 1)
      if (!valid) assigned.delete(materialIndex)
      return valid
    })
  }
  if (!assign(0)) return false
  return materials.every((entry, index) => assigned.has(index)
      || clauses.some(clause => clause.repeatable && matchesMaterialDescriptor(entry, clause.descriptor)))
}

function linkRatingCanTotal(materials, targetRating) {
  let totals = new Set([0])
  for (const entry of materials) {
    const linkValue = extraDeckKind(entry.card) === 'Link' ? Number(entry.card.linkvalue || 1) : 1
    const contributions = linkValue > 1 ? [1, linkValue] : [1]
    totals = new Set([...totals].flatMap(total => contributions.map(value => total + value)))
  }
  return totals.has(targetRating)
}

function findLegalExtraDeckMaterials(target, fieldEntries) {
  const kind = extraDeckKind(target)
  const requirement = materialRequirement(target)
  const clauses = materialClauses(requirement)
  const candidates = fieldEntries.filter(entry => isMonsterCard(entry.card))
  const minimumMaterials = kind === 'Link'
    ? Math.max(1, clauses.reduce((sum, clause) => sum + clause.minimum, 0))
    : 2
  const materialSets = combinations(candidates, minimumMaterials)

  for (const materials of materialSets) {
    if (kind === 'Synchro') {
      const targetLevel = Number(target.level)
      if (!targetLevel || materials.some(entry => !Number(entry.card.level))) continue
      if (materials.reduce((sum, entry) => sum + Number(entry.card.level), 0) !== targetLevel) continue
      if (!materials.some(entry => (entry.card.type || '').toLowerCase().includes('tuner'))) continue
      if (!satisfiesClauses(materials, clauses)) continue
      return materials
    }

    if (kind === 'Xyz') {
      const requiredLevel = Number(requirement.match(/level\s+(\d+)/i)?.[1] || target.level)
      if (materials.some(entry => isTokenCard(entry.card))) continue
      if (!requiredLevel || materials.some(entry => Number(entry.card.level) !== requiredLevel)) continue
      if (!satisfiesClauses(materials, clauses)) continue
      return materials
    }

    if (kind === 'Link') {
      const targetRating = Number(target.linkvalue)
      if (!targetRating || !linkRatingCanTotal(materials, targetRating)) continue
      const includingRule = requirement.match(/including (?:an? )?(.+)$/i)?.[1]
      const baseRequirement = requirement.replace(/,?\s*including .+$/i, '')
      if (!satisfiesClauses(materials, materialClauses(baseRequirement))) continue
      if (includingRule && !materials.some(entry => matchesMaterialDescriptor(entry, includingRule))) continue
      return materials
    }
  }
  return null
}

function displayZoneName(zone) {
  return {
    monsterZone: 'Monster Zone',
    extraMonsterZone: 'Extra Monster Zone',
    spellTrapZone: 'Spell & Trap Zone',
    pendulumZone: 'Pendulum Zone',
    hand: 'Hand',
    graveyard: 'Graveyard',
    banished: 'Banished',
    extraDeck: 'Face-up Extra Deck',
  }[zone] || 'Current location'
}

function normalizeEffectText(text) {
  return (text || '')
      .replace(/[“”]/g, '"')
      .replace(/’/g, "'")
      .replace(/\r\n/g, '\n')
      .trim()
}

function usesThisCardFromHand(text) {
  const lower = text.toLowerCase()
  return /\bdiscard this card\b/.test(lower)
      || /\bthis card (?:is|was) in your hand\b/.test(lower)
      || /\b(?:reveal|send|special summon|normal summon|activate) this card (?:in|from) your hand\b/.test(lower)
      || /\bthis card from your hand\b/.test(lower)
      || /\bwhile this card is in your hand\b/.test(lower)
}

function optionalEffectClause(text) {
  const levelMatch = text.match(/\bthen you can increase its Level by (\d+)\b/i)
  if (!levelMatch) return null
  return {
    label: `Increase its Level by ${levelMatch[1]}`,
    levelIncrease: Number(levelMatch[1]),
  }
}

function effectSourceZone(text, card, fallbackZone) {
  const lower = text.toLowerCase()
  if (usesThisCardFromHand(text)) {
    return 'Hand'
  }
  if (/\btribute this card\b/.test(lower)) {
    return 'Monster Zone'
  }
  if (/\b(?:if|when) this card (?:is|was) (?:normal or special |normal |special |tribute |flip |ritual |fusion |synchro |xyz |link |pendulum )?summoned\b/.test(lower)) {
    return 'Monster Zone'
  }
  if (/continuous (?:trap|spell)/.test(lower) && /if this card is/.test(lower)) {
    return 'Spell & Trap Zone'
  }
  if (/(?:in|from|sent to|while .* in) (?:the |your )?(?:gy|graveyard)/.test(lower)) {
    return 'Graveyard'
  }
  if (/(?:this card|it) is banished|from your banished|among your banished/.test(lower)) {
    return 'Banished'
  }
  if (fallbackZone) return fallbackZone
  return /(spell|trap)/i.test(card.type || '') ? 'Spell & Trap Zone' : 'Monster Zone'
}

function cardEffectOptions(card, zone = null, entry = null) {
  const normalized = normalizeEffectText(card.description)
  let sections = [{ text: normalized, sourceZone: null }]
  const pendulumMatch = normalized.match(
      /\[?\s*pendulum effect\s*\]?\s*([\s\S]*?)\[?\s*monster effect\s*\]?\s*([\s\S]*)/i,
  )
  if (pendulumMatch) {
    sections = [
      { text: pendulumMatch[1], sourceZone: 'Pendulum Zone' },
      { text: pendulumMatch[2], sourceZone: 'Monster Zone' },
    ]
  }

  const options = sections.flatMap(section => section.text
      .split(/(?<=[.!?])\s+(?=[A-Z"[])|\n+/)
      .map(text => text.trim())
      .filter(text => text.length > 0)
      .filter(text => !/^[●•▪◦-]\s*/.test(text))
      .filter(text => !/^you can only (?:use|activate)\b/i.test(text))
      .filter(text => !/^you cannot special summon\b/i.test(text))
      .filter(text => /\b(you can|add|draw|summon|place|set|send|discard|tribute|banish|destroy|return|target|activate)\b/i.test(text))
      .map(text => ({
        text,
        sourceZone: effectSourceZone(text, card, section.sourceZone),
      })))

  return options.filter(effect => {
    const source = effect.sourceZone.toLowerCase()
    if (zone === 'graveyard') return source === 'graveyard'
    if (zone === 'banished') return source === 'banished'
    if (zone === 'monsterZone' || zone === 'extraMonsterZone') return source.includes('monster zone')
    if (zone === 'hand') return source.includes('hand') && !isOnSummonEffect(effect)
    if (zone === 'pendulumZone') return source.includes('pendulum zone')
    if (zone === 'spellTrapZone') {
      return source.includes('spell & trap zone')
          || source.includes('pendulum zone')
          || (/(spell|trap)/i.test(card.type || '') && !source.includes('graveyard') && !source.includes('banished'))
          || Boolean(entry?.treatedAs && source.includes('spell & trap zone'))
    }
    return true
  })
}

function effectUsageKey(entry, effect) {
  return `${entry?.instanceId ?? entry?.card?.id ?? 'card'}:${effect.text.toLowerCase()}`
}

function isOnSummonEffect(effect) {
  return /\b(?:if|when) this card (?:is|was) (?:normal or special |normal |special |tribute |flip |ritual |fusion |synchro |xyz |link |pendulum )?summoned\b/i
      .test(effect.text)
}

function onSummonEffectOptions(card, entry) {
  return cardEffectOptions(card, 'monsterZone', entry).filter(isOnSummonEffect)
}

function canActivateFromZone(entry, zone) {
  const text = (entry.card.description || '').toLowerCase()
  const reason = entry.moveReason || ''
  if (zone === 'graveyard') {
    return text.split(/(?<=\.)\s+|\n+/).some(effect => {
      if (/destroyed(?: by battle| by card effect)? and sent to (?:the |your )?(?:gy|graveyard)/.test(effect)) {
        return reason === 'destroy'
      }
      if (/if this card is discarded/.test(effect)) {
        return reason === 'discard'
      }
      if (/if this card is tributed/.test(effect)) {
        return reason === 'tribute'
      }
      if (/used as fusion material/.test(effect)) {
        return reason === 'fusion-material'
      }
      return /(?:sent to|in|from|while .* in) (?:the |your )?(?:gy|graveyard)/.test(effect)
          || /banish (?:this card|it) from (?:the |your )?(?:gy|graveyard)/.test(effect)
    })
  }
  if (zone === 'banished') {
    return /(?:if|when|while) (?:this card|it) is banished/.test(text)
        || /(?:from|among) your banished/.test(text)
  }
  return cardEffectOptions(entry.card, zone, entry).length > 0
}

function SimulatedZones({
  zones,
  activatedEffects,
  effectInteractionPending,
  onRequestEffect,
  onOpenExtraDeckSummon,
  onOpenPendulumSummon,
  onMoveCard,
  onRemoveCard,
}) {
  const zoneDefinitions = [
    { key: 'monsterZone', label: 'Monsters' },
    { key: 'extraMonsterZone', label: 'Extra Monster Zone' },
    { key: 'spellTrapZone', label: 'Spells & Traps' },
    { key: 'pendulumZone', label: 'Pendulum Zones' },
    { key: 'hand', label: 'Hand' },
    { key: 'graveyard', label: 'Graveyard' },
    { key: 'banished', label: 'Banished' },
    { key: 'extraDeck', label: 'Face-up Extra Deck' },
  ]

  return (
      <div className="simulated-zones">
        {zoneDefinitions.map(zone => (
            <section key={zone.key} className={`sim-zone ${zone.key}`}>
              <div className="sim-zone-heading">
                <span>{zone.label}</span>
                <div className="sim-zone-heading-actions">
                  {zone.key === 'monsterZone' && (
                      <button type="button" className="extra-deck-summon-btn" onClick={onOpenExtraDeckSummon}>
                        Synchro / Xyz / Link
                      </button>
                  )}
                  {zone.key === 'pendulumZone' && (
                      <button type="button" className="pendulum-summon-btn" onClick={onOpenPendulumSummon}>
                        Pendulum Summon
                      </button>
                  )}
                  <span className="sim-zone-count">{zones[zone.key].length}</span>
                </div>
              </div>
              <div className="sim-zone-cards">
                {zone.key === 'pendulumZone' && zones.pendulumZone.length < 2 && (
                    Array.from({ length: 2 - zones.pendulumZone.length }, (_, index) => (
                        <div key={`empty-scale-${index}`} className="sim-zone-empty scale-slot">Empty Pendulum Zone</div>
                    ))
                )}
                {zones[zone.key].length === 0 && zone.key !== 'pendulumZone' ? (
                    <div className="sim-zone-empty">No cards</div>
                ) : zones[zone.key].map(entry => {
                  const effects = cardEffectOptions(entry.card, zone.key, entry)
                  const liveEffect = canActivateFromZone(entry, zone.key)
                  const used = effects.length > 0 && effects.every(effect =>
                    activatedEffects.includes(effectUsageKey(entry, effect)))
                  return (
                      <div key={entry.instanceId} className="sim-zone-card-shell">
                        <button
                            type="button"
                            className={`sim-zone-card${liveEffect ? ' live' : ''}${used ? ' used' : ''}`}
                            onClick={() => liveEffect && !used && !effectInteractionPending
                              && onRequestEffect(entry, zone.key)}
                            disabled={!liveEffect || used || effectInteractionPending}
                            title={liveEffect
                              ? used
                                ? 'This zone effect was already used in this route'
                                : `Activate ${entry.card.name} from the ${zone.label}`
                              : 'This card has no effect that activates from this zone'}
                        >
                          <span className="sim-zone-card-name">{entry.card.name}</span>
                          {['monsterZone', 'extraMonsterZone'].includes(zone.key) && isMonsterCard(entry.card) && (
                              <span className="sim-zone-monster-stats">
                                {extraDeckKind(entry.card) === 'Link'
                                  ? `LINK-${entry.card.linkvalue ?? '?'}`
                                  : extraDeckKind(entry.card) === 'Xyz'
                                    ? `Rank ${entry.card.level ?? '?'}`
                                    : `Level ${entry.card.level ?? '?'}`}
                                {isTokenCard(entry.card) ? ' · Token Monster' : ''}
                                {entry.overlayMaterials?.length > 0 ? ` · ${entry.overlayMaterials.length} material${entry.overlayMaterials.length === 1 ? '' : 's'}` : ''}
                              </span>
                          )}
                          {entry.treatedAs && <span className="sim-zone-treated-as">Treated as {entry.treatedAs}</span>}
                          {liveEffect && (
                              <span className="sim-zone-effect-state">{used ? 'Effect used' : 'Effect available'}</span>
                          )}
                        </button>
                        <div className="sim-zone-card-actions">
                          {['monsterZone', 'extraMonsterZone', 'spellTrapZone', 'pendulumZone'].includes(zone.key)
                              && !isTokenCard(entry.card)
                              && manualPlacementZones(entry.card).includes('hand') && (
                              <button type="button" onClick={() => onMoveCard(entry, zone.key, 'hand')}>To Hand</button>
                          )}
                          {zone.key === 'hand' && manualPlacementZones(entry.card).includes('monsterZone') && (
                              <button type="button" onClick={() => onMoveCard(entry, zone.key, 'monsterZone')}>Summon</button>
                          )}
                          {zone.key === 'hand' && /(spell|trap)/i.test(entry.card.type || '') && (
                              <button type="button" onClick={() => onMoveCard(entry, zone.key, 'spellTrapZone')}>Set</button>
                          )}
                          {zone.key === 'hand' && manualPlacementZones(entry.card).includes('pendulumZone') && (
                              <button type="button" onClick={() => onMoveCard(entry, zone.key, 'pendulumZone')}>Set Scale</button>
                          )}
                          <button
                              type="button"
                              className="remove-zone-card"
                              onClick={() => onRemoveCard(entry, zone.key)}
                          >
                            Remove
                          </button>
                        </div>
                      </div>
                  )
                })}
              </div>
            </section>
        ))}
      </div>
  )
}

function PendulumSummonPicker({ zones, onSummon, onCancel }) {
  const [selectedIds, setSelectedIds] = useState([])
  const range = pendulumScaleRange(zones)
  const eligible = pendulumEligibleEntries(zones)
  const mainOpen = 5 - zones.monsterZone.length
  const extraMonsterOpen = zones.extraMonsterZone.length === 0 ? 1 : 0
  const linkedOpen = linkedMainZoneCapacity(zones)
  const selected = eligible.filter(entry => selectedIds.includes(entry.instanceId))
  const selectedFromExtra = selected.filter(entry => entry.pendulumSource === 'extraDeck').length
  const selectedFromHand = selected.length - selectedFromExtra
  const selectionFits = selectedFromExtra <= extraMonsterOpen + linkedOpen
      && selectedFromHand + Math.max(0, selectedFromExtra - extraMonsterOpen) <= mainOpen

  function toggle(entry) {
    setSelectedIds(current => current.includes(entry.instanceId)
      ? current.filter(id => id !== entry.instanceId)
      : [...current, entry.instanceId])
  }

  return (
      <div className="material-picker-backdrop" role="presentation">
        <section className="pendulum-summon-picker" role="dialog" aria-modal="true" aria-labelledby="pendulum-summon-title">
          <div className="material-picker-header">
            <div>
              <div className="material-picker-kicker">Pendulum Summon</div>
              <h3 id="pendulum-summon-title">Pendulum Summon</h3>
            </div>
            <button type="button" className="material-picker-close" onClick={onCancel}>Close</button>
          </div>

          <div className="pendulum-scale-summary">
            {range
              ? <><strong>Scales {range.low} and {range.high}</strong><span>Eligible Levels: {range.low + 1}–{range.high - 1}</span></>
              : <span>Place two Pendulum Monsters with valid scales before Pendulum Summoning.</span>}
          </div>
          <div className="pendulum-capacity-summary">
            <span>{mainOpen} open Main Monster Zone{mainOpen === 1 ? '' : 's'}</span>
            <span>{extraMonsterOpen} open Extra Monster Zone</span>
            <span>{linkedOpen} open linked Main Monster Zone{linkedOpen === 1 ? '' : 's'}</span>
          </div>

          <div className="pendulum-summon-options">
            {eligible.length === 0 ? (
                <div className="material-no-results">No monsters in your Hand or face-up Extra Deck have a Level between the scales.</div>
            ) : eligible.map(entry => {
              const checked = selectedIds.includes(entry.instanceId)
              return (
                  <label key={entry.instanceId} className={`pendulum-summon-option${checked ? ' selected' : ''}`}>
                    <input type="checkbox" checked={checked} onChange={() => toggle(entry)} />
                    <span>
                      <strong>{entry.card.name}</strong>
                      <small>Level {entry.card.level} · From {entry.pendulumSource === 'hand' ? 'Hand' : 'face-up Extra Deck'}</small>
                    </span>
                  </label>
              )
            })}
          </div>

          {!selectionFits && (
              <div className="material-picker-error">
                Face-up Extra Deck monsters require an open Extra Monster Zone or a Main Monster Zone a Link Monster points to.
              </div>
          )}
          <div className="pendulum-summon-footer">
            <span>{selected.length} selected</span>
            <button
                type="button"
                className="pendulum-summon-confirm"
                disabled={selected.length === 0 || !selectionFits}
                onClick={() => onSummon(selected)}
            >
              Pendulum Summon
            </button>
          </div>
        </section>
      </div>
  )
}

function EffectPicker({ selection, activatedEffects, busy, onChoose, onCancel }) {
  if (!selection) return null

  return (
      <div className="material-picker-backdrop" role="presentation">
        <section className="effect-picker" role="dialog" aria-modal="true" aria-labelledby="effect-picker-title">
          <div className="material-picker-header">
            <div>
              <div className="material-picker-kicker">
                {selection.automatic ? 'Summon Effect Triggered' : 'Activate Card Effect'}
              </div>
              <h3 id="effect-picker-title">{selection.card.name}</h3>
            </div>
            <button type="button" className="material-picker-close" onClick={onCancel} disabled={busy}>Close</button>
          </div>
          <div className="effect-picker-copy">
            {selection.automatic
              ? 'This card was summoned. Choose which on-summon effect you want to resolve.'
              : 'Choose the exact effect you want to activate.'}
          </div>
          <div className="effect-picker-options">
            {selection.effects.map((effect, index) => {
              const used = activatedEffects.includes(effectUsageKey(selection.entry, effect))
              const unavailableReason = effect.unavailableReason || ''
              const unavailable = used || Boolean(unavailableReason)
              const optionalClause = optionalEffectClause(effect.text)
              if (optionalClause) {
                return (
                    <div
                        key={`${effect.sourceZone}-${effect.text}`}
                        className={`effect-choice effect-choice-optional${unavailable ? ' is-used' : ''}`}
                    >
                      <span className="effect-choice-number">Effect {index + 1}</span>
                      <span className="effect-choice-zone">From: {effect.sourceZone}</span>
                      <span className="effect-choice-text">{effect.text}</span>
                      {used
                        ? <span className="effect-choice-used">Already activated</span>
                        : unavailableReason
                          ? <span className="effect-choice-unavailable">Unavailable: {unavailableReason}</span>
                        : (
                            <div className="optional-effect-actions">
                              <button type="button" onClick={() => onChoose(effect, false)} disabled={busy}>
                                Special Summon only
                              </button>
                              <button type="button" className="apply-optional" onClick={() => onChoose(effect, true)} disabled={busy}>
                                Special Summon + {optionalClause.label.toLowerCase()}
                              </button>
                            </div>
                          )}
                    </div>
                )
              }
              return (
                  <button
                      key={`${effect.sourceZone}-${effect.text}`}
                      type="button"
                      className="effect-choice"
                      onClick={() => onChoose(effect, false)}
                      disabled={unavailable || busy}
                      title={unavailableReason || undefined}
                  >
                    <span className="effect-choice-number">Effect {index + 1}</span>
                    <span className="effect-choice-zone">From: {effect.sourceZone}</span>
                    <span className="effect-choice-text">{effect.text}</span>
                    {used && <span className="effect-choice-used">Already activated</span>}
                    {!used && unavailableReason && (
                        <span className="effect-choice-unavailable">Unavailable: {unavailableReason}</span>
                    )}
                  </button>
              )
            })}
          </div>
        </section>
      </div>
  )
}

function ExtraDeckSummonPicker({ zones, summonRestrictions, onSummon, onCancel }) {
  const [kind, setKind] = useState('Synchro')
  const [search, setSearch] = useState('')
  const [results, setResults] = useState([])
  const [loading, setLoading] = useState(false)
  const [searchError, setSearchError] = useState(null)

  useEffect(() => {
    const term = search.trim()
    if (!term) {
      setResults([])
      setSearchError(null)
      return undefined
    }

    const controller = new AbortController()
    const timeout = setTimeout(async () => {
      setLoading(true)
      setSearchError(null)
      try {
        const response = await fetch(
            `${API}/yugioh/card/substring?name=${encodeURIComponent(term)}`,
            { signal: controller.signal },
        )
        if (!response.ok) throw new Error('Search failed')
        const cards = await response.json()
        setResults((Array.isArray(cards) ? cards : [])
            .filter(card => extraDeckKind(card) === kind)
            .slice(0, 20))
      } catch (error) {
        if (error.name !== 'AbortError') {
          setResults([])
          setSearchError('Could not search Extra Deck monsters.')
        }
      } finally {
        if (!controller.signal.aborted) setLoading(false)
      }
    }, 250)

    return () => {
      clearTimeout(timeout)
      controller.abort()
    }
  }, [kind, search])

  const availableMaterials = [...zones.monsterZone, ...zones.extraMonsterZone]
      .filter(entry => isMonsterCard(entry.card))

  return (
      <div className="material-picker-backdrop" role="presentation">
        <section className="extra-deck-picker" role="dialog" aria-modal="true" aria-labelledby="extra-deck-title">
          <div className="material-picker-header">
            <div>
              <div className="material-picker-kicker">Extra Deck Summon</div>
              <h3 id="extra-deck-title">Synchro / Xyz / Link Summon</h3>
            </div>
            <button type="button" className="material-picker-close" onClick={onCancel}>Close</button>
          </div>

          <div className="extra-deck-controls">
            <label>
              Summon type
              <select value={kind} onChange={event => setKind(event.target.value)}>
                <option>Synchro</option>
                <option>Xyz</option>
                <option>Link</option>
              </select>
            </label>
            <label>
              Search monster
              <input
                  autoFocus
                  value={search}
                  onChange={event => setSearch(event.target.value)}
                  placeholder={`Type a ${kind} monster name...`}
              />
            </label>
          </div>

          <div className="extra-deck-field-summary">
            <strong>Available monsters</strong>
            {availableMaterials.length === 0
              ? <span>No monsters or Tokens are currently in the Monster Zone.</span>
              : <span>{availableMaterials.map(entry => `${entry.card.name} (${extraDeckKind(entry.card) === 'Link' ? `LINK-${entry.card.linkvalue ?? '?'}` : `Level ${entry.card.level ?? '?'}`})`).join(' · ')}</span>}
          </div>

          <div className="extra-deck-results" role="listbox" aria-label={`${kind} monster suggestions`}>
            {loading && <div className="material-picker-status">Searching...</div>}
            {searchError && <div className="material-picker-error">{searchError}</div>}
            {!loading && !searchError && search.trim() && results.length === 0 && (
                <div className="material-no-results">No matching {kind} monsters found.</div>
            )}
            {!loading && results.map(card => {
              const materials = findLegalExtraDeckMaterials(card, availableMaterials)
              const requirement = materialRequirement(card)
              const restrictionReason = summonRestrictionReason(summonRestrictions, card, true)
              return (
                  <button
                      key={card.id ?? card.name}
                      type="button"
                      role="option"
                      aria-selected="false"
                      className={`extra-deck-result${materials && !restrictionReason ? ' legal' : ' illegal'}`}
                      disabled={!materials || Boolean(restrictionReason)}
                      onClick={() => onSummon(card, materials)}
                  >
                    <span className="extra-deck-result-heading">
                      <strong>{card.name}</strong>
                      <span>{kind === 'Link' ? `LINK-${card.linkvalue ?? '?'}` : `${kind === 'Xyz' ? 'Rank' : 'Level'} ${card.level ?? '?'}`}</span>
                    </span>
                    <span className="extra-deck-requirement">{requirement}</span>
                    <span className="extra-deck-legality">
                      {restrictionReason
                        ? `Locked: ${restrictionReason}`
                        : materials
                        ? `Summon using: ${materials.map(entry => entry.card.name).join(' + ')}`
                        : 'Current Monster Zone does not meet this card’s requirements.'}
                    </span>
                  </button>
              )
            })}
          </div>
        </section>
      </div>
  )
}

function MaterialPicker({
  pendingOption,
  materialPlan,
  loading,
  error,
  selections,
  search,
  onSearchChange,
  onAddMaterial,
  onRemoveMaterial,
  onConfirm,
  onCancel,
  zones,
}) {
  if (!pendingOption) return null

  const complete = materialPlan?.slots.every(
      (slot, index) => (selections[index]?.length ?? 0) === slot.count,
  )
  const availableFrom = (materialPlan?.availableFrom || '').toLowerCase()
  const hasOpenCardPool = availableFrom.includes('deck') && !availableFrom.includes('extra deck')
  const isTributePlan = (materialPlan?.action || '').toLowerCase().includes('tribute')

  function simulatedLocations(card) {
    return ZONE_KEYS.filter(zone => {
      const availableToken = zone === 'extraDeck'
        ? 'extra deck'
        : zone === 'monsterZone'
          ? 'field'
          : zone === 'spellTrapZone'
            ? 'field'
            : zone === 'pendulumZone'
              ? 'field'
            : zone
      return availableFrom.includes(availableToken)
        && zones[zone].some(entry => entry.card.id === card.id)
    })
  }

  function availableCopies(card) {
    if (hasOpenCardPool) return 3
    return simulatedLocations(card).reduce(
        (total, zone) => total + zones[zone].filter(entry => entry.card.id === card.id).length,
        0,
    )
  }

  function selectedCopies(card) {
    return Object.values(selections)
        .flat()
        .filter(selectedCard => selectedCard.id === card.id)
        .length
  }

  return (
      <div className="material-picker-backdrop" role="presentation">
        <section className="extra-deck-picker route-cost-picker" role="dialog" aria-modal="true" aria-labelledby="material-picker-title">
          <div className="material-picker-header">
            <div>
              <div className="material-picker-kicker">Pay Route Cost</div>
              <h3 id="material-picker-title">{pendingOption.card.name}</h3>
            </div>
            <button type="button" className="material-picker-close" onClick={onCancel}>Close</button>
          </div>

          {loading ? (
              <div className="material-picker-status">Finding legal materials...</div>
          ) : error ? (
              <div className="material-picker-error">{error}</div>
          ) : materialPlan && (
              <>
                <div className="extra-deck-controls route-cost-controls">
                  <label>
                    Cost requirement
                    <span className="route-cost-control">{materialPlan.action}</span>
                  </label>
                  <label>
                    Search legal material
                    <input
                        autoFocus
                        value={search}
                        onChange={event => onSearchChange(event.target.value)}
                        placeholder="Type a card name..."
                    />
                  </label>
                </div>

                <div className="extra-deck-field-summary route-cost-summary">
                  <strong>Payment details</strong>
                  <span>Available from: {materialPlan.availableFrom} · Send to: {materialPlan.destination}</span>
                  {materialPlan.restrictions?.map(restriction => (
                      <span key={restriction} className="route-cost-restriction">{restriction}</span>
                  ))}
                </div>

                <div className="extra-deck-results route-cost-results">
                  {materialPlan.slots.map((slot, slotIndex) => {
                    const selectedCards = selections[slotIndex] ?? []
                    const filteredCards = slot.eligibleCards.filter(card =>
                      card.name.toLowerCase().includes(search.toLowerCase())
                        && availableCopies(card) > 0)
                    const resultLimit = search.trim() && isTributePlan ? 50 : 10
                    const matchingCards = filteredCards.slice(0, resultLimit)
                    const hiddenResultCount = filteredCards.length - matchingCards.length
                    return (
                        <section key={`${slot.requirement}-${slotIndex}`} className="route-cost-slot">
                          <div className="route-cost-slot-heading">
                            <strong>{slot.count}x {slot.requirement}</strong>
                            <span>{selectedCards.length}/{slot.count} selected</span>
                          </div>
                          {selectedCards.length > 0 && (
                              <div className="selected-materials">
                                {selectedCards.map((card, selectedIndex) => (
                                    <button
                                        key={`${card.id ?? card.name}-${selectedIndex}`}
                                        type="button"
                                        onClick={() => onRemoveMaterial(slotIndex, selectedIndex)}
                                    >
                                      {card.name} ×
                                    </button>
                                ))}
                              </div>
                          )}
                          {hiddenResultCount > 0 && (
                              <div className="material-result-cap">
                                Showing {resultLimit} of {filteredCards.length} legal options. Type in the search bar to narrow the results.
                              </div>
                          )}
                          {matchingCards.length === 0 ? (
                              <div className="material-no-results">No legal cards match this filter.</div>
                          ) : matchingCards.map(card => {
                            const locations = simulatedLocations(card)
                            const selectedCount = selectedCards.filter(selectedCard => selectedCard.id === card.id).length
                            const unavailable = selectedCards.length >= slot.count
                                || selectedCopies(card) >= availableCopies(card)
                            return (
                                <button
                                    key={card.id ?? card.name}
                                    type="button"
                                    className={`extra-deck-result route-cost-result${selectedCount > 0 ? ' selected' : ' legal'}`}
                                    onClick={() => onAddMaterial(slotIndex, card)}
                                    disabled={unavailable}
                                >
                                  <span className="extra-deck-result-heading">
                                    <strong>{card.name}</strong>
                                    <span>{locations.length > 0
                                      ? locations.map(displayZoneName).join(' / ')
                                      : materialPlan.availableFrom}</span>
                                  </span>
                                  <span className="extra-deck-requirement">Matches: {slot.requirement}</span>
                                  <span className="extra-deck-legality">
                                    {selectedCount > 0
                                      ? `Selected${selectedCount > 1 ? ` ×${selectedCount}` : ''}`
                                      : `Legal material · ${availableCopies(card)} available`}
                                  </span>
                                </button>
                            )
                          })}
                        </section>
                    )
                  })}
                </div>
                <div className="material-picker-actions">
                  <button type="button" className="material-cancel-btn" onClick={onCancel}>Cancel</button>
                  <button type="button" className="material-confirm-btn" onClick={onConfirm} disabled={!complete}>
                    Pay Cost &amp; Continue
                  </button>
                </div>
              </>
          )}
        </section>
      </div>
  )
}

function ComboOptionCard({ option, comboPath, onChooseCard, timingLockReason }) {
  const alreadyUsed = option.oncePerTurn && comboPath.some(
      step => step.name?.toLowerCase() === option.card.name?.toLowerCase(),
  )
  const unavailable = alreadyUsed || Boolean(timingLockReason)

  return (
      <button
          type="button"
          className={`combo-option-card${unavailable ? ' unavailable' : ''}`}
          onClick={() => onChooseCard(option)}
          disabled={unavailable}
          title={alreadyUsed
            ? 'This once-per-turn card was already used in this route'
            : timingLockReason || undefined}
      >
        <div className="combo-option-label">{option.label}</div>
        <div className="combo-option-name">{option.card.name}</div>
        <div className="combo-option-type">{option.card.type}</div>
        {alreadyUsed && <div className="combo-option-used">Already used this turn</div>}
        {timingLockReason && <div className="combo-option-timing-lock">{timingLockReason}</div>}
        <div className="combo-option-meta">
          {option.timing && (
              <span className={`combo-meta-chip${option.timing === 'Immediate' ? '' : ' delayed'}`}>
                {option.timing}
              </span>
          )}
          {option.sourceZone && (
              <span className="combo-meta-chip">From: {option.sourceZone}</span>
          )}
          {option.destination && option.destination !== 'Varies' && (
              <span className="combo-meta-chip">To: {option.destination}</span>
          )}
        </div>
        {option.cost && (
            <div className="combo-option-cost">
              <span className="combo-option-cost-label">Cost</span>
              {option.cost}
            </div>
        )}
        <div className="combo-option-reason">{option.reason}</div>
      </button>
  )
}

function SummonTargetPicker({
  sourceCard,
  options,
  comboPath,
  timingLockReason,
  onChooseCard,
  onCancel,
}) {
  const [search, setSearch] = useState('')
  const normalizedSearch = search.trim().toLowerCase()
  const filteredOptions = options.filter(option => {
    if (!normalizedSearch) return true
    return [
      option.card.name,
      option.card.type,
      option.card.archetype,
      option.label,
      option.reason,
      option.cost,
    ].some(value => (value || '').toLowerCase().includes(normalizedSearch))
  })
  const resultLimit = 50
  const visibleOptions = filteredOptions.slice(0, resultLimit)

  function chooseOption(option) {
    onCancel()
    onChooseCard(option)
  }

  return (
      <div className="material-picker-backdrop" role="presentation">
        <section
            className="extra-deck-picker route-cost-picker summon-target-picker"
            role="dialog"
            aria-modal="true"
            aria-labelledby="summon-target-picker-title"
        >
          <div className="material-picker-header">
            <div>
              <div className="material-picker-kicker">Choose Summon Target</div>
              <h3 id="summon-target-picker-title">{sourceCard.name}</h3>
            </div>
            <button type="button" className="material-picker-close" onClick={onCancel}>Close</button>
          </div>

          <div className="summon-target-controls">
            <label htmlFor="summon-target-search">Search legal target</label>
            <input
                id="summon-target-search"
                className="material-search"
                autoFocus
                value={search}
                onChange={event => setSearch(event.target.value)}
                placeholder="Type a card name, type, archetype, or material..."
            />
          </div>

          <div className="extra-deck-field-summary summon-target-summary">
            <strong>{filteredOptions.length} matching targets</strong>
            <span>{options.length} legal summon routes are available from this effect.</span>
          </div>

          <div className="summon-target-results" aria-label="Legal summon targets">
            {visibleOptions.length === 0 ? (
                <div className="material-no-results">No legal summon targets match this filter.</div>
            ) : visibleOptions.map(option => (
                <ComboOptionCard
                    key={option.card.id ?? option.card.name}
                    option={option}
                    comboPath={comboPath}
                    onChooseCard={chooseOption}
                    timingLockReason={timingLockReason(option)}
                />
            ))}
            {filteredOptions.length > resultLimit && (
                <div className="material-result-cap">
                  Showing {resultLimit} of {filteredOptions.length} targets. Narrow the search to find a specific card.
                </div>
            )}
          </div>
        </section>
      </div>
  )
}

function CardDetail({
  card,
  oncePerTurn,
  extender,
  comboPath,
  comboOptions,
  comboLoading,
  canGoBack,
  onChooseCard,
  onBackCombo,
  zones,
  summonRestrictions,
  activatedEffects,
  effectInteractionPending,
  onRequestEffect,
  activeEffect,
  onOpenExtraDeckSummon,
  onOpenPendulumSummon,
  onMoveCard,
  onRemoveCard,
  onClearZones,
  placementPending,
  onPlaceSearchResult,
}) {
  const [summonTargetPickerOpen, setSummonTargetPickerOpen] = useState(false)
  const color = typeColor(card.type)
  const t = (card.type || '').toLowerCase()
  const isMonster = t.includes('monster')
  const isLink = t.includes('link')
  const isPendulum = t.includes('pendulum')
  const hasImage = Boolean(card.cardImageUrl)
  const placementOptions = manualPlacementZones(card)
  const hasBadges = oncePerTurn || extender === 'summon extender' || extender === 'add extender' || card.staple === true || card.weight > 0
  const continuingOptions = comboOptions?.filter(option => option.label !== 'ender') ?? []
  const enderOptions = comboOptions?.filter(option => option.label === 'ender') ?? []

  useEffect(() => {
    setSummonTargetPickerOpen(false)
  }, [card.id, activeEffect?.text])

  function timingLockReason(option) {
    const destination = (option.destination || '').toLowerCase()
    const targetType = (option.card.type || '').toLowerCase()
    const mainMonsterCount = zones.monsterZone.length
    const spellTrapCount = zones.spellTrapZone.length
    const pendulumCount = zones.pendulumZone.length
    const fromExtraDeck = destination.includes('extra deck') || isExtraDeckMonster(option.card)
    const isSpecialSummon = destination.includes('monster zone')
        && (fromExtraDeck || /special summon|fusion summon/i.test(activeEffect?.text || ''))
    const activeRestriction = summonRestrictionReason(
        summonRestrictions,
        option.card,
        fromExtraDeck,
        isSpecialSummon,
    )

    if (activeRestriction) return activeRestriction

    if (destination.includes('monster zone')
        && targetType.includes('monster')
        && !targetType.includes('fusion')
        && mainMonsterCount >= 5) {
      return 'All 5 Main Monster Zones are occupied'
    }
    if (destination.includes('pendulum zone') && (pendulumCount >= 2 || spellTrapCount + pendulumCount >= 5)) {
      return pendulumCount >= 2 ? 'Both Pendulum Zones are occupied' : 'No shared Spell & Trap slot is available'
    }
    if (destination.includes('spell & trap zone') && spellTrapCount >= 5 - pendulumCount) {
      return `Only ${5 - pendulumCount} Spell & Trap slots are available with the current Pendulum Zones`
    }
    return null
  }

  const sourceCanOpenSummonPicker = /monster|spell/i.test(card.type || '')
      && /(?:fusion|special) summon/i.test(activeEffect?.text || '')
  const summonTargetOptions = sourceCanOpenSummonPicker
    ? comboOptions.filter(option => {
      const destination = (option.destination || '').toLowerCase()
      return isMonsterCard(option.card)
          && (destination.includes('monster zone') || /fusion target|summon/i.test(option.label || ''))
    })
    : []
  const useSummonTargetPicker = summonTargetOptions.length >= 10
  const summonTargetIds = new Set(summonTargetOptions.map(option => option.card.id ?? option.card.name))
  const inlineContinuingOptions = useSummonTargetPicker
    ? continuingOptions.filter(option => !summonTargetIds.has(option.card.id ?? option.card.name))
    : continuingOptions
  const inlineEnderOptions = useSummonTargetPicker
    ? enderOptions.filter(option => !summonTargetIds.has(option.card.id ?? option.card.name))
    : enderOptions

  return (
      <div className="wiki-card">
        <div className="wiki-banner" style={{ background: color }}>
          <h2 className="wiki-name">{card.name}</h2>
          {placementPending && (
              <div className="card-placement-actions" aria-label={`Place ${card.name}`}>
                <span className="card-placement-label">Place in</span>
                <div className="card-placement-buttons">
                  {placementOptions.map(destination => {
                    const unavailableReason = placementCapacityReason(zones, destination)
                    return (
                        <button
                            key={destination}
                            type="button"
                            disabled={Boolean(unavailableReason)}
                            title={unavailableReason || `Place in ${ZONE_LABELS[destination]}`}
                            onClick={() => onPlaceSearchResult(destination)}
                        >
                          {ZONE_BUTTON_LABELS[destination]}
                        </button>
                    )
                  })}
                </div>
                <span className="card-placement-label">Zone</span>
              </div>
          )}
        </div>

        <div className="wiki-body">
          <div className="wiki-primary-col">
            <div className="wiki-image-col">
              <div className="wiki-image-frame" style={{ borderColor: color }}>
                {hasImage ? (
                  <img
                      src={card.cardImageUrl}
                      alt={card.name}
                      className="wiki-image"
                  />
                ) : (
                  <div className="wiki-image-placeholder">Image unavailable</div>
                )}
              </div>
            </div>

            <div className="wiki-info-col">
            <div className="wiki-info-panel">
              <WikiRow label="Type">{card.type}</WikiRow>

              {isMonster && (
                  <>
                    {card.attribute && <WikiRow label="Attribute">{card.attribute}</WikiRow>}
                    {card.race && <WikiRow label="Race">{card.race}</WikiRow>}
                    {!isLink && card.level != null && (
                        <WikiRow label="Level"><Stars count={card.level} /></WikiRow>
                    )}
                    {isPendulum && card.scale != null && (
                        <WikiRow label="Pendulum Scale">{card.scale}</WikiRow>
                    )}
                    <WikiRow label="ATK / DEF">
                      {card.atk ?? '?'} {!isLink && <>/ {card.def ?? '?'}</>}
                    </WikiRow>
                    {isLink && card.linkvalue != null && (
                        <WikiRow label="Link Rating">{card.linkvalue}</WikiRow>
                    )}
                    {isLink && card.linkmarkers?.length > 0 && (
                        <WikiRow label="Link Arrows">{card.linkmarkers.join('  ')}</WikiRow>
                    )}
                  </>
              )}

              {card.archetype && (
                  <WikiRow label="Archetype">{card.archetype}</WikiRow>
              )}

              {hasBadges && (
                  <div className="wiki-row">
                    <span className="wiki-row-label">Tags</span>
                    <span className="wiki-row-value wiki-badges">
                      {oncePerTurn && <Badge label="Once Per Turn" color="#7a1d6b" />}
                      {extender === 'summon extender' && <Badge label="Summon Extender" color="#1d6b3a" />}
                      {extender === 'add extender' && <Badge label="Add Extender" color="#1a4f8b" />}
                      {card.staple === true && <Badge label="Staple" color="#b5891e" />}
                      {card.weight > 0 && <Badge label={`Weight ${card.weight}`} color="#334" />}
                    </span>
                  </div>
              )}
            </div>

            <div className="wiki-section-header" style={{ background: color }}>
              Description
            </div>
            <div className="wiki-card-text">{card.description}</div>

            <div className="wiki-section-header" style={{ background: color }}>
              Build Combo
            </div>
            <div className="wiki-combo-panel">
              <div className="combo-builder-top">
                <div className="combo-path" aria-label="Combo path">
                  {comboPath.map((step, index) => (
                      <span key={`${step.id ?? step.name}-${index}`} className="combo-path-step">
                        <span className={`combo-path-chip${index === comboPath.length - 1 ? ' active' : ''}`}>
                          <span className="combo-path-index">{index + 1}</span>
                          <span className="combo-path-name">{step.name}</span>
                        </span>
                        {index < comboPath.length - 1 && <span className="combo-path-arrow">→</span>}
                      </span>
                  ))}
                </div>
                <button
                    type="button"
                    className="combo-back-btn"
                    onClick={onBackCombo}
                    disabled={!canGoBack}
                >
                  Back
                </button>
              </div>
              <div className="effect-action-bar">
                <button
                    type="button"
                    className="activate-effect-btn"
                    onClick={() => onRequestEffect()}
                    disabled={effectInteractionPending}
                >
                  {effectInteractionPending
                    ? 'Checking Effect...'
                    : activeEffect ? 'Choose Another Effect' : 'Activate an Effect'}
                </button>
                {activeEffect ? (
                    <div className="active-effect-summary">
                      <span>Resolving from {activeEffect.sourceZone}</span>
                      <p>{activeEffect.text}</p>
                    </div>
                ) : (
                    <div className="effect-action-hint">
                      Select an effect before choosing the next card in the combo.
                    </div>
                )}
              </div>

              {summonRestrictions.length > 0 && (
                  <div className="active-summon-restrictions" role="status">
                    <strong>Active summon restriction</strong>
                    {summonRestrictions.map(restriction => (
                        <span key={restriction.id}>{restriction.sourceCard}: {restriction.text}</span>
                    ))}
                  </div>
              )}

              {comboLoading ? (
                  <div className="wiki-combo-loading">Loading next combo options...</div>
              ) : !activeEffect ? (
                  <div className="wiki-combo-empty">
                    No effect is active. Choose which effect you want to use.
                  </div>
              ) : comboOptions && comboOptions.length > 0 ? (
                  <div className="combo-option-groups">
                    {useSummonTargetPicker && (
                        <section className="combo-option-group summon-target-launch-group">
                          <div className="combo-option-group-title">Special Summon Targets</div>
                          <button
                              type="button"
                              className="summon-target-launch"
                              onClick={() => setSummonTargetPickerOpen(true)}
                          >
                            <span>Search summon targets</span>
                            <strong>{summonTargetOptions.length}</strong>
                            <small>Choose by card name, type, archetype, or material requirement</small>
                          </button>
                        </section>
                    )}
                    {inlineContinuingOptions.length > 0 && (
                        <section className="combo-option-group">
                          <div className="combo-option-group-title">Continue Combo</div>
                          <div className="combo-option-grid">
                            {inlineContinuingOptions.map(option => (
                                <ComboOptionCard
                                    key={option.card.id ?? option.card.name}
                                    option={option}
                                    comboPath={comboPath}
                                    onChooseCard={onChooseCard}
                                    timingLockReason={timingLockReason(option)}
                                />
                            ))}
                          </div>
                        </section>
                    )}
                    {inlineEnderOptions.length > 0 && (
                        <section className="combo-option-group enders">
                          <div className="combo-option-group-title">Combo Enders</div>
                          <div className="combo-option-grid">
                            {inlineEnderOptions.map(option => (
                                <ComboOptionCard
                                    key={option.card.id ?? option.card.name}
                                    option={option}
                                    comboPath={comboPath}
                                    onChooseCard={onChooseCard}
                                    timingLockReason={timingLockReason(option)}
                                />
                            ))}
                          </div>
                        </section>
                    )}
                  </div>
              ) : (
                  <div className="wiki-combo-empty">
                    This effect resolves without a selectable follow-up card.
                  </div>
              )}
            </div>

            {summonTargetPickerOpen && (
                <SummonTargetPicker
                    sourceCard={card}
                    options={summonTargetOptions}
                    comboPath={comboPath}
                    timingLockReason={timingLockReason}
                    onChooseCard={onChooseCard}
                    onCancel={() => setSummonTargetPickerOpen(false)}
                />
            )}

            </div>
          </div>

          <aside className="wiki-zones-col" aria-label="Current field and other zones">
            <div className="wiki-section-header zone-section-header zone-section-heading-row">
              <span>Current Field &amp; Other Zones</span>
              <button type="button" className="clear-zones-btn" onClick={onClearZones}>Clear Zones</button>
            </div>
            <SimulatedZones
                zones={zones}
                activatedEffects={activatedEffects}
                effectInteractionPending={effectInteractionPending}
                onRequestEffect={onRequestEffect}
                onOpenExtraDeckSummon={onOpenExtraDeckSummon}
                onOpenPendulumSummon={onOpenPendulumSummon}
                onMoveCard={onMoveCard}
                onRemoveCard={onRemoveCard}
            />
          </aside>
        </div>
      </div>
  )
}

function CardListItem({ card, selected, onClick }) {
  const color = typeColor(card.type)
  return (
      <div
          className={`card-list-item${selected ? ' selected' : ''}`}
          style={{ borderLeftColor: color }}
          onClick={onClick}
      >
        <div className="card-list-name">{card.name}</div>
        <div className="card-list-type" style={{ color }}>{card.type}</div>
      </div>
  )
}

const API = import.meta.env.VITE_API_URL || 'http://localhost:8080'

export default function App() {
  const comboRequestId = useRef(0)
  const zoneInstanceId = useRef(0)
  const effectRequestInFlight = useRef(false)
  const effectResolutionInFlight = useRef(false)
  const [query, setQuery] = useState('')
  const [mode, setMode] = useState('search')
  const [cards, setCards] = useState([])
  const [selected, setSelected] = useState(null)
  const [oncePerTurn, setOncePerTurn] = useState(null)
  const [extender, setExtender] = useState(null)
  const [comboPath, setComboPath] = useState([])
  const [comboOptions, setComboOptions] = useState([])
  const [zones, setZones] = useState(emptyZones)
  const [comboHistory, setComboHistory] = useState([])
  const [activatedEffects, setActivatedEffects] = useState([])
  const [summonRestrictions, setSummonRestrictions] = useState([])
  const [activeZoneContext, setActiveZoneContext] = useState(null)
  const [selectedEntry, setSelectedEntry] = useState(null)
  const [activeEffect, setActiveEffect] = useState(null)
  const [effectSelection, setEffectSelection] = useState(null)
  const [effectInteractionPending, setEffectInteractionPending] = useState(false)
  const [placementSelection, setPlacementSelection] = useState(null)
  const [, setSummonEffectQueue] = useState([])
  const [pendingOption, setPendingOption] = useState(null)
  const [materialPlan, setMaterialPlan] = useState(null)
  const [materialSelections, setMaterialSelections] = useState({})
  const [materialSearch, setMaterialSearch] = useState('')
  const [materialLoading, setMaterialLoading] = useState(false)
  const [materialError, setMaterialError] = useState(null)
  const [extraDeckPickerOpen, setExtraDeckPickerOpen] = useState(false)
  const [pendulumPickerOpen, setPendulumPickerOpen] = useState(false)
  const [comboLoading, setComboLoading] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  function zoneEntry(card, moveReason = 'placed', treatedAs = null) {
    return { instanceId: `zone-${++zoneInstanceId.current}`, card, moveReason, treatedAs }
  }

  function snapshotComboState() {
    return {
      comboPath,
      comboOptions,
      zones,
      activatedEffects,
      summonRestrictions,
      activeZoneContext,
      selected,
      selectedEntry,
      activeEffect,
    }
  }

  async function fetchCardExtras(card) {
    setOncePerTurn(null)
    setExtender(null)
    try {
      const [optRes, extRes] = await Promise.all([
        fetch(`${API}/yugioh/card/onceprturn?name=${encodeURIComponent(card.name)}`),
        fetch(`${API}/yugioh/card/pattern?name=${encodeURIComponent(card.name)}`),
      ])
      if (optRes.ok) {
        const oncePerTurnText = (await optRes.text()).trim()
        setOncePerTurn(oncePerTurnText.toLowerCase() === 'not once per turn' ? null : oncePerTurnText)
      }
      if (extRes.ok) setExtender((await extRes.text()).trim())
    } catch {
      // extras are non-critical
    }
  }

  async function fetchComboOptions(card, zoneContext = null, effect = null) {
    const requestId = ++comboRequestId.current
    setComboLoading(true)
    setComboOptions([])
    try {
      const effectText = typeof effect === 'string' ? effect : effect?.text
      const zoneQuery = zoneContext ? `&zone=${encodeURIComponent(zoneContext)}` : ''
      const effectQuery = effectText ? `&effect=${encodeURIComponent(effectText)}` : ''
      const res = await fetch(
          `${API}/yugioh/card/combos?name=${encodeURIComponent(card.name)}${zoneQuery}${effectQuery}`,
      )
      if (!res.ok) throw new Error('Failed to load combo options')
      const data = await res.json()
      if (comboRequestId.current === requestId) {
        let options = Array.isArray(data) ? data : []
        if (Array.isArray(effect?.legalSummonTargets)) {
          const legalTargetNames = new Set(
              effect.legalSummonTargets.map(name => name.toLocaleLowerCase()),
          )
          options = options.filter(option => {
            const destination = (option.destination || '').toLowerCase()
            const isSummonTarget = isMonsterCard(option.card)
                && (destination.includes('monster zone') || /fusion target|summon/i.test(option.label || ''))
            return !isSummonTarget || legalTargetNames.has(option.card.name.toLocaleLowerCase())
          })
        }
        setComboOptions(options)
      }
    } catch {
      if (comboRequestId.current === requestId) {
        setComboOptions([])
      }
    } finally {
      if (comboRequestId.current === requestId) {
        setComboLoading(false)
      }
    }
  }

  async function openRootCard(card) {
    setSelected(card)
    setSelectedEntry(null)
    setComboPath([card])
    setComboOptions([])
    setActiveZoneContext(null)
    setActiveEffect(null)
    setEffectSelection(null)
    setPlacementSelection(card)
    setSummonEffectQueue([])
    setExtraDeckPickerOpen(false)
    setPendulumPickerOpen(false)
    closeMaterialPicker()
    setError(null)
    await fetchCardExtras(card)
  }

  function placeSearchResult(destination) {
    const card = placementSelection
    if (!card || !ZONE_KEYS.includes(destination)) return
    if (!manualPlacementZones(card).includes(destination)) {
      setError(`${card.name} cannot be placed in ${ZONE_LABELS[destination]}.`)
      return
    }
    const capacityReason = placementCapacityReason(zones, destination)
    if (capacityReason) {
      setError(capacityReason)
      return
    }

    const moveReasons = {
      hand: 'added-to-hand',
      monsterZone: 'manual-summon',
      extraMonsterZone: 'manual-extra-zone-summon',
      spellTrapZone: 'set-this-turn',
      pendulumZone: 'placed-in-pendulum-zone',
      graveyard: 'manually-sent-to-graveyard',
      banished: 'manually-banished',
      extraDeck: 'placed-in-extra-deck',
    }
    const entry = zoneEntry(
        card,
        moveReasons[destination],
        destination === 'pendulumZone' ? 'Pendulum Card' : null,
    )
    setComboHistory(prev => [...prev, snapshotComboState()])
    setZones(previous => ({ ...previous, [destination]: [...previous[destination], entry] }))
    setSelectedEntry(entry)
    setActiveZoneContext(destination)
    setPlacementSelection(null)
    setError(null)
    if (destination === 'monsterZone' || destination === 'extraMonsterZone') {
      promptOnSummonEffects(card, entry, destination)
    }
  }

  function clearZones() {
    setZones(emptyZones())
    setComboHistory([])
    setActivatedEffects([])
    setSummonRestrictions([])
    setActiveZoneContext(null)
    setSelectedEntry(null)
    setActiveEffect(null)
    setEffectSelection(null)
    setPlacementSelection(null)
    setSummonEffectQueue([])
    setComboOptions([])
    setComboPath(selected ? [selected] : [])
    setExtraDeckPickerOpen(false)
    setPendulumPickerOpen(false)
    closeMaterialPicker()
    setError(null)
  }

  function promptOnSummonEffects(card, entry, zone = 'monsterZone') {
    if (!entry) return
    const effects = onSummonEffectOptions(card, entry)
    if (effects.length === 0) return
    setEffectSelection({
      card,
      entry,
      zone,
      effects,
      automatic: true,
    })
  }

  function promptSimultaneousSummonEffects(entries) {
    const triggered = entries.flatMap(entry => {
      const effects = onSummonEffectOptions(entry.card, entry)
      return effects.length > 0 ? [{ card: entry.card, entry, zone: 'monsterZone', effects, automatic: true }] : []
    })
    if (triggered.length === 0) return
    setEffectSelection(triggered[0])
    setSummonEffectQueue(triggered.slice(1))
  }

  function moveZoneCard(entry, fromZone, toZone) {
    if (!ZONE_KEYS.includes(fromZone) || !ZONE_KEYS.includes(toZone)) return
    if (!zones[fromZone].some(candidate => candidate.instanceId === entry.instanceId)) return
    if (!manualPlacementZones(entry.card).includes(toZone)) {
      setError(`${entry.card.name} cannot be moved to ${ZONE_LABELS[toZone]}.`)
      return
    }
    if (toZone === 'monsterZone' && zones.monsterZone.length >= 5) {
      setError('All 5 Main Monster Zones are occupied.')
      return
    }
    if (toZone === 'extraMonsterZone' && zones.extraMonsterZone.length >= 1) {
      setError('The Extra Monster Zone is occupied.')
      return
    }
    if (toZone === 'pendulumZone'
        && (zones.pendulumZone.length >= 2 || zones.spellTrapZone.length + zones.pendulumZone.length >= 5)) {
      setError(zones.pendulumZone.length >= 2
        ? 'Both Pendulum Zones are occupied.'
        : 'No shared Spell & Trap slot is available.')
      return
    }
    if (toZone === 'spellTrapZone' && zones.spellTrapZone.length >= 5 - zones.pendulumZone.length) {
      setError(`Only ${5 - zones.pendulumZone.length} Spell & Trap slots are available with the current Pendulum Zones.`)
      return
    }

    const movedEntry = {
      ...entry,
      moveReason: toZone === 'monsterZone'
        ? 'normal-summon'
        : toZone === 'pendulumZone'
          ? 'placed-in-pendulum-zone'
          : `moved-to-${toZone}`,
      treatedAs: toZone === 'pendulumZone' ? 'Pendulum Card' : null,
    }
    setComboHistory(prev => [...prev, snapshotComboState()])
    setZones(prev => ({
      ...prev,
      [fromZone]: prev[fromZone].filter(candidate => candidate.instanceId !== entry.instanceId),
      [toZone]: [...prev[toZone], movedEntry],
    }))
    setSelected(entry.card)
    setSelectedEntry(movedEntry)
    setActiveZoneContext(toZone)
    setActiveEffect(null)
    setComboOptions([])
    setError(null)
    if (toZone === 'monsterZone') {
      promptOnSummonEffects(entry.card, movedEntry)
    }
  }

  function removeZoneCard(entry, zone) {
    if (!ZONE_KEYS.includes(zone) || !zones[zone].some(candidate => candidate.instanceId === entry.instanceId)) return
    setComboHistory(prev => [...prev, snapshotComboState()])
    setZones(previous => ({
      ...previous,
      [zone]: previous[zone].filter(candidate => candidate.instanceId !== entry.instanceId),
    }))
    setActivatedEffects(previous => previous.filter(key => !key.startsWith(`${entry.instanceId}:`)))
    if (selectedEntry?.instanceId === entry.instanceId) {
      setSelectedEntry(null)
      setActiveZoneContext(null)
      setActiveEffect(null)
      setComboOptions([])
    }
    setError(null)
  }

  async function performExtraDeckSummon(card, materials) {
    const activeRestriction = summonRestrictionReason(summonRestrictions, card, true)
    if (activeRestriction) {
      setError(activeRestriction)
      return
    }
    const legalMaterials = findLegalExtraDeckMaterials(card, [...zones.monsterZone, ...zones.extraMonsterZone])
    const selectedIds = new Set(materials.map(entry => entry.instanceId))
    const stillLegal = legalMaterials
        && legalMaterials.length === materials.length
        && legalMaterials.every(entry => selectedIds.has(entry.instanceId))
    if (!stillLegal) {
      setError(`The current field no longer meets ${card.name}'s material requirements.`)
      return
    }

    const summonKind = extraDeckKind(card)
    const summonedEntry = {
      ...zoneEntry(card, `${summonKind.toLowerCase()}-summon`),
      overlayMaterials: summonKind === 'Xyz' ? materials : [],
    }
    setComboHistory(prev => [...prev, snapshotComboState()])
    setZones(prev => {
      const next = Object.fromEntries(ZONE_KEYS.map(zoneName => [zoneName, [...prev[zoneName]]]))
      next.monsterZone = next.monsterZone.filter(entry => !selectedIds.has(entry.instanceId))
      next.extraMonsterZone = next.extraMonsterZone.filter(entry => !selectedIds.has(entry.instanceId))
      for (const material of materials) {
        for (const overlay of material.overlayMaterials || []) {
          if (!isTokenCard(overlay.card)) {
            next.graveyard.push({ ...overlay, moveReason: 'detached-with-xyz', treatedAs: null })
          }
        }
        if (summonKind !== 'Xyz') {
          if (isTokenCard(material.card)) continue
          if ((material.card.type || '').toLowerCase().includes('pendulum')) {
            next.extraDeck.push({ ...material, moveReason: 'pendulum-replacement', treatedAs: null, overlayMaterials: [] })
          } else {
            next.graveyard.push({ ...material, moveReason: 'extra-deck-material', treatedAs: null, overlayMaterials: [] })
          }
        }
      }
      next.monsterZone.push(summonedEntry)
      return next
    })
    setSelected(card)
    setSelectedEntry(summonedEntry)
    setComboPath(prev => [...prev, card])
    setComboOptions([])
    setActiveEffect(null)
    setActiveZoneContext('monsterZone')
    setExtraDeckPickerOpen(false)
    setError(null)
    promptOnSummonEffects(card, summonedEntry)
    await fetchCardExtras(card)
  }

  function openPendulumSummonPicker() {
    const range = pendulumScaleRange(zones)
    if (!range) {
      setError('Place two Pendulum Monsters with valid scales first.')
      return
    }
    if (range.low === range.high) {
      setError('Matching scales do not create a Level range for a Pendulum Summon.')
      return
    }
    setError(null)
    setPendulumPickerOpen(true)
  }

  function performPendulumSummon(entries) {
    const eligible = pendulumEligibleEntries(zones)
    const eligibleById = new Map(eligible.map(entry => [entry.instanceId, entry]))
    const chosen = entries.map(entry => eligibleById.get(entry.instanceId)).filter(Boolean)
    if (chosen.length !== entries.length || chosen.length === 0) {
      setError('The selected Pendulum Summon is no longer legal.')
      return
    }

    const restrictedEntry = chosen.find(entry => summonRestrictionReason(
        summonRestrictions,
        entry.card,
        entry.pendulumSource === 'extraDeck',
    ))
    if (restrictedEntry) {
      setError(summonRestrictionReason(
          summonRestrictions,
          restrictedEntry.card,
          restrictedEntry.pendulumSource === 'extraDeck',
      ))
      return
    }

    const fromExtra = chosen.filter(entry => entry.pendulumSource === 'extraDeck')
    const fromHand = chosen.filter(entry => entry.pendulumSource === 'hand')
    const mainOpen = 5 - zones.monsterZone.length
    const extraMonsterOpen = zones.extraMonsterZone.length === 0 ? 1 : 0
    const linkedOpen = linkedMainZoneCapacity(zones)
    if (fromExtra.length > extraMonsterOpen + linkedOpen
        || fromHand.length + Math.max(0, fromExtra.length - extraMonsterOpen) > mainOpen) {
      setError('There are not enough legal Monster Zones for that Pendulum Summon.')
      return
    }

    const chosenIds = new Set(chosen.map(entry => entry.instanceId))
    const extraZoneSummons = extraMonsterOpen ? fromExtra.slice(0, 1) : []
    const extraZoneIds = new Set(extraZoneSummons.map(entry => entry.instanceId))
    const summonedEntries = chosen.map(entry => ({
      ...entry,
      pendulumSource: undefined,
      moveReason: 'pendulum-summon',
      treatedAs: null,
      overlayMaterials: [],
    }))

    setComboHistory(prev => [...prev, snapshotComboState()])
    setZones(prev => {
      const next = Object.fromEntries(ZONE_KEYS.map(zoneName => [zoneName, [...prev[zoneName]]]))
      next.hand = next.hand.filter(entry => !chosenIds.has(entry.instanceId))
      next.extraDeck = next.extraDeck.filter(entry => !chosenIds.has(entry.instanceId))
      next.extraMonsterZone.push(...summonedEntries.filter(entry => extraZoneIds.has(entry.instanceId)))
      next.monsterZone.push(...summonedEntries.filter(entry => !extraZoneIds.has(entry.instanceId)))
      return next
    })
    setPendulumPickerOpen(false)
    setError(null)
    const lastSummoned = summonedEntries.at(-1)
    setSelected(lastSummoned.card)
    setSelectedEntry(lastSummoned)
    setActiveZoneContext(extraZoneIds.has(lastSummoned.instanceId) ? 'extraMonsterZone' : 'monsterZone')
    setComboOptions([])
    setActiveEffect(null)
    promptSimultaneousSummonEffects(summonedEntries)
  }

  async function finishComboChoice(
      option,
      paidMaterials = [],
      materialDestination = null,
      paymentReason = 'send',
  ) {
    const card = option.card
    const destination = (option.destination || '').toLowerCase()
    const destinationZone = destination.includes('pendulum zone')
      ? 'pendulumZone'
      : destination.includes('spell & trap zone')
        ? 'spellTrapZone'
      : destination.includes('monster zone')
        ? 'monsterZone'
        : destination.includes('hand')
          ? 'hand'
        : null
    const fromExtraDeck = destination.includes('extra deck') || isExtraDeckMonster(card)
    const isSpecialSummon = destinationZone === 'monsterZone'
        && (fromExtraDeck || /special summon|fusion summon/i.test(activeEffect?.text || ''))
    const activeRestriction = summonRestrictionReason(
        summonRestrictions,
        card,
        fromExtraDeck,
        isSpecialSummon,
    )
    if (activeRestriction) {
      setError(activeRestriction)
      return
    }
    const treatedAs = destination.includes('continuous trap')
      ? 'Continuous Trap'
      : destination.includes('continuous spell')
        ? 'Continuous Spell'
        : destination.includes('pendulum zone')
          ? 'Pendulum Card'
          : null
    const destinationEntry = destinationZone
      ? zoneEntry(card, destinationZone === 'monsterZone' ? 'summoned' : 'effect-resolution', treatedAs)
      : null
    const effectText = (activeEffect?.text || '').toLowerCase()
    const movesSourceToBackrow = Boolean(selectedEntry
        && effectText.includes('place this card')
        && /continuous (?:trap|spell)/.test(effectText)
        && !zones.spellTrapZone.some(entry => entry.instanceId === selectedEntry.instanceId))
    if (destinationZone === 'monsterZone' && zones.monsterZone.length >= 5) {
      setError('All 5 Main Monster Zones are occupied.')
      return
    }
    if (destinationZone === 'pendulumZone'
        && (zones.pendulumZone.length >= 2 || zones.spellTrapZone.length + zones.pendulumZone.length >= 5)) {
      setError(zones.pendulumZone.length >= 2
        ? 'Both Pendulum Zones are occupied.'
        : 'No shared Spell & Trap slot is available.')
      return
    }
    if (destinationZone === 'spellTrapZone') {
      const requiredSlots = 1 + (movesSourceToBackrow ? 1 : 0)
      const availableSlots = 5 - zones.pendulumZone.length - zones.spellTrapZone.length
      if (requiredSlots > availableSlots) {
        setError(`This effect needs ${requiredSlots} open Spell & Trap slots, but only ${availableSlots} are available.`)
        return
      }
    }
    setComboHistory(prev => [...prev, snapshotComboState()])
    setZones(prev => {
      const next = Object.fromEntries(ZONE_KEYS.map(zone => [zone, [...prev[zone]]]))

      for (const material of paidMaterials) {
        let originZone = null
        for (const zoneName of ZONE_KEYS) {
          const existingIndex = next[zoneName].findIndex(entry => entry.card.id === material.id)
          if (existingIndex >= 0) {
            next[zoneName].splice(existingIndex, 1)
            originZone = zoneName
            break
          }
        }
        if (materialDestination === 'Graveyard') {
          const isToken = (material.type || '').toLowerCase().includes('token')
              || material.name.toLowerCase().includes('token')
          if (isToken) {
            continue
          }
          const isFieldPendulum = ['monsterZone', 'spellTrapZone'].includes(originZone)
              && (material.type || '').toLowerCase().includes('pendulum')
          if (isFieldPendulum) {
            next.extraDeck.push(zoneEntry(material, 'pendulum-replacement'))
          } else {
            next.graveyard.push(zoneEntry(material, paymentReason))
          }
        } else if (materialDestination === 'Banished') {
          next.banished.push(zoneEntry(material, 'banish'))
        }
      }

      if (selectedEntry
          && effectText.includes('place this card')
          && /continuous (?:trap|spell)/.test(effectText)) {
        for (const zoneName of ZONE_KEYS) {
          next[zoneName] = next[zoneName].filter(entry => entry.instanceId !== selectedEntry.instanceId)
        }
        next.spellTrapZone.push({
          ...selectedEntry,
          moveReason: 'placed-as-continuous',
          treatedAs: effectText.includes('continuous spell') ? 'Continuous Spell' : 'Continuous Trap',
        })
      }

      if (destinationEntry) {
        next[destinationZone].push(destinationEntry)
      }
      return next
    })
    setSelected(card)
    setSelectedEntry(destinationEntry)
    setComboPath(prev => [...prev, card])
    setComboOptions([])
    setActiveEffect(null)
    setActiveZoneContext(destinationZone)
    if (destinationZone === 'monsterZone') {
      promptOnSummonEffects(card, destinationEntry)
    }
    await fetchCardExtras(card)
  }

  async function chooseComboOption(option) {
    const isPaidFusion = option.cost?.includes('Materials:')
        && (option.card.type || '').toLowerCase().includes('fusion')
    const selfTributeAlreadyPaid = /\btribute this card\b/i.test(activeEffect?.text || '')
        && /^(?:you can\s+)?tribute this card[;.]?$/i.test((option.cost || '').trim())
    if (!option.cost || selfTributeAlreadyPaid) {
      await finishComboChoice(option)
      return
    }

    setPendingOption(option)
    setMaterialPlan(null)
    setMaterialSelections({})
    setMaterialSearch('')
    setMaterialError(null)
    setMaterialLoading(true)
    try {
      const plannerPath = isPaidFusion ? 'fusion-materials' : 'cost-materials'
      const effectQuery = activeEffect?.text
        ? `&effect=${encodeURIComponent(activeEffect.text)}`
        : ''
      const response = await fetch(
          `${API}/yugioh/card/${plannerPath}?source=${encodeURIComponent(selected.name)}&target=${encodeURIComponent(option.card.name)}${effectQuery}`,
      )
      if (!response.ok) throw new Error('Could not determine legal cards for this route cost.')
      setMaterialPlan(await response.json())
    } catch (requestError) {
      setMaterialError(requestError.message)
    } finally {
      setMaterialLoading(false)
    }
  }

  function closeMaterialPicker() {
    setPendingOption(null)
    setMaterialPlan(null)
    setMaterialSelections({})
    setMaterialSearch('')
    setMaterialError(null)
    setMaterialLoading(false)
  }

  function addMaterial(slotIndex, card) {
    setMaterialSelections(prev => {
      const current = prev[slotIndex] ?? []
      const maximum = materialPlan.slots[slotIndex].count
      if (current.length >= maximum) return prev
      return { ...prev, [slotIndex]: [...current, card] }
    })
  }

  function removeMaterial(slotIndex, selectedIndex) {
    setMaterialSelections(prev => ({
      ...prev,
      [slotIndex]: (prev[slotIndex] ?? []).filter((_, index) => index !== selectedIndex),
    }))
  }

  async function confirmMaterialPayment() {
    if (!pendingOption || !materialPlan) return
    const complete = materialPlan.slots.every(
        (slot, index) => (materialSelections[index]?.length ?? 0) === slot.count,
    )
    if (!complete) return

    const selectedMaterials = materialPlan.slots.flatMap((_, index) => materialSelections[index] ?? [])
    const zoneCards = new Map(
        ZONE_KEYS.flatMap(zone => zones[zone])
            .map(entry => [entry.card.name.toLowerCase(), entry.card]),
    )
    const hydratedCards = new Map(zoneCards)
    const missingNames = [...new Set(selectedMaterials
        .map(card => card.name)
        .filter(name => !hydratedCards.has(name.toLowerCase())))]
    if (missingNames.length > 0) {
      setMaterialLoading(true)
      try {
        await Promise.all(missingNames.map(async name => {
          const response = await fetch(`${API}/yugioh/card?name=${encodeURIComponent(name)}`)
          if (!response.ok) throw new Error(`Could not load ${name}.`)
          const card = await response.json()
          hydratedCards.set(card.name.toLowerCase(), card)
        }))
      } catch (requestError) {
        setMaterialError(requestError.message)
        setMaterialLoading(false)
        return
      }
    }
    const materials = selectedMaterials.map(card => hydratedCards.get(card.name.toLowerCase()) ?? card)
    const option = pendingOption
    const destination = materialPlan.destination
    const action = (materialPlan.action || '').toLowerCase()
    const isFusionPayment = (option.card.type || '').toLowerCase().includes('fusion')
    const paymentReason = isFusionPayment
      ? 'fusion-material'
      : action.includes('discard')
        ? 'discard'
        : action.includes('tribute')
          ? 'tribute'
          : action.includes('destroy')
            ? 'destroy'
            : action.includes('banish')
              ? 'banish'
              : 'send'
    closeMaterialPicker()
    await finishComboChoice(option, materials, destination, paymentReason)
  }

  function effectNeedsPrerequisiteCheck(effect) {
    const costClause = effect.text.split(';', 1)[0]
    return /\b(?:discard|tribute)\b/i.test(costClause)
        || /\b(?:fusion|synchro|xyz) summon\b/i.test(effect.text)
  }

  async function effectPrerequisiteResult(card, effect, zone) {
    if (!effectNeedsPrerequisiteCheck(effect)) {
      return { available: true, reason: '', legalSummonTargets: null }
    }
    const zonesPayload = Object.fromEntries(ZONE_KEYS.map(zoneName => [
      zoneName,
      zones[zoneName].map(zoneEntryValue => zoneEntryValue.card.name),
    ]))
    try {
      const response = await fetch(`${API}/yugioh/card/effect-prerequisites`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          source: card.name,
          effect: effect.text,
          sourceZone: zone,
          zones: zonesPayload,
        }),
      })
      if (!response.ok) throw new Error('Prerequisite check failed')
      return await response.json()
    } catch {
      return {
        available: false,
        reason: 'The app could not verify this effect’s prerequisites.',
        legalSummonTargets: [],
      }
    }
  }

  async function requestEffectActivation(entry = selectedEntry, zone = activeZoneContext) {
    if (effectRequestInFlight.current || effectResolutionInFlight.current || effectSelection) return
    if (!entry || !zone) {
      setError('Place the card in a zone before activating one of its effects.')
      return
    }
    const card = entry?.card ?? selected
    if (!card) return
    const resolvedEntry = entry ?? { instanceId: `card-${card.id ?? card.name}`, card, moveReason: 'selected' }
    const effects = cardEffectOptions(card, zone, resolvedEntry)
    if (effects.length === 0) {
      setError(`${card.name} has no effect that can be activated from ${displayZoneName(zone)}.`)
      return
    }
    effectRequestInFlight.current = true
    setEffectInteractionPending(true)
    try {
      setError(null)
      setSelected(card)
      setSelectedEntry(resolvedEntry)
      setActiveZoneContext(zone)
      const checkedEffects = await Promise.all(effects.map(async effect => {
        const prerequisite = await effectPrerequisiteResult(card, effect, zone)
        return {
          ...effect,
          prerequisitesChecked: true,
          legalSummonTargets: prerequisite.legalSummonTargets,
          ...(!prerequisite.available ? { unavailableReason: prerequisite.reason } : {}),
        }
      }))
      setEffectSelection({ card, entry: resolvedEntry, zone, effects: checkedEffects })
      await fetchCardExtras(card)
    } finally {
      effectRequestInFlight.current = false
      setEffectInteractionPending(false)
    }
  }

  function closeEffectPicker() {
    setEffectSelection(null)
    setSummonEffectQueue(queue => {
      if (queue.length === 0) return queue
      const [next, ...remaining] = queue
      setEffectSelection(next)
      return remaining
    })
  }

  async function chooseEffect(effect, applyOptional = false) {
    if (!effectSelection || effectRequestInFlight.current || effectResolutionInFlight.current) return
    effectResolutionInFlight.current = true
    setEffectInteractionPending(true)
    try {
      const { card, entry, zone } = effectSelection
    const prerequisite = effect.prerequisitesChecked
      ? {
          available: !effect.unavailableReason,
          reason: effect.unavailableReason || '',
          legalSummonTargets: effect.legalSummonTargets ?? null,
        }
      : await effectPrerequisiteResult(card, effect, zone)
    const validatedEffect = {
      ...effect,
      prerequisitesChecked: true,
      legalSummonTargets: prerequisite.legalSummonTargets,
      ...(!prerequisite.available ? { unavailableReason: prerequisite.reason } : {}),
    }
    const unavailableReason = validatedEffect.unavailableReason
    if (unavailableReason) {
      setEffectSelection(previous => previous ? {
        ...previous,
        effects: previous.effects.map(candidate => candidate.text === effect.text
          ? validatedEffect
          : candidate),
      } : previous)
      setError(unavailableReason)
      return
    }
    const placesSelfAsContinuousTrap = Boolean(entry
        && ['monsterZone', 'extraMonsterZone'].includes(zone)
        && /place this card you control[\s\S]*in your Spell & Trap Zones? as face-up Continuous Traps?/i.test(effect.text))
    if (placesSelfAsContinuousTrap && placementCapacityReason(zones, 'spellTrapZone')) {
      setError('The Spell & Trap Zones are full, so this effect cannot place the card there.')
      return
    }
    if (entry
        && zone === 'spellTrapZone'
        && /special summon this card/i.test(effect.text)
        && placementCapacityReason(zones, 'monsterZone')) {
      setError('The Main Monster Zones are full, so this card cannot be Special Summoned.')
      return
    }
    setComboHistory(prev => [...prev, snapshotComboState()])
    setActivatedEffects(prev => [...prev, effectUsageKey(entry, effect)])
    const activatedRestrictions = activationSummonRestrictions(card, effect.text)
    if (activatedRestrictions.length > 0) {
      setSummonRestrictions(previous => {
        const existingIds = new Set(previous.map(restriction => restriction.id))
        return [...previous, ...activatedRestrictions.filter(restriction => !existingIds.has(restriction.id))]
      })
    }
    const optionalClause = optionalEffectClause(effect.text)
    setActiveEffect({
      ...validatedEffect,
      optionalApplied: Boolean(applyOptional && optionalClause),
    })
    setComboOptions([])
    setSelected(card)
    setSelectedEntry(entry)
    setComboPath(prev => prev.at(-1)?.id === card.id ? prev : [...prev, card])
    closeEffectPicker()

    let resolvedZone = zone
    let summonedEntry = null
    const paysSelfTribute = Boolean(entry
        && ['monsterZone', 'extraMonsterZone'].includes(zone)
        && /\btribute this card\b/i.test(effect.text))
    if (paysSelfTribute) {
      const tributedEntry = {
        ...entry,
        moveReason: 'tribute',
        treatedAs: null,
        overlayMaterials: [],
      }
      setZones(prev => {
        const next = Object.fromEntries(ZONE_KEYS.map(zoneName => [zoneName, [...prev[zoneName]]]))
        next[zone] = next[zone].filter(candidate => candidate.instanceId !== entry.instanceId)
        if (!isTokenCard(entry.card)) next.graveyard.push(tributedEntry)
        return next
      })
      resolvedZone = isTokenCard(entry.card) ? null : 'graveyard'
      setSelectedEntry(isTokenCard(entry.card) ? null : tributedEntry)
    } else if (placesSelfAsContinuousTrap) {
      const continuousTrapEntry = {
        ...entry,
        moveReason: 'placed-as-continuous-trap',
        treatedAs: 'Continuous Trap',
        overlayMaterials: [],
      }
      setZones(prev => {
        const next = Object.fromEntries(ZONE_KEYS.map(zoneName => [
          zoneName,
          prev[zoneName].filter(candidate => candidate.instanceId !== entry.instanceId),
        ]))
        next.spellTrapZone.push(continuousTrapEntry)
        return next
      })
      resolvedZone = 'spellTrapZone'
      setSelectedEntry(continuousTrapEntry)
    } else if (entry
        && zone === 'spellTrapZone'
        && /special summon this card/i.test(effect.text)) {
      summonedEntry = {
        ...entry,
        card: applyOptional && optionalClause?.levelIncrease
          ? {
              ...entry.card,
              level: Number(entry.card.level || 0) + optionalClause.levelIncrease,
            }
          : entry.card,
        moveReason: 'special-summon',
        treatedAs: null,
      }
      setZones(prev => {
        const next = Object.fromEntries(ZONE_KEYS.map(zoneName => [
          zoneName,
          prev[zoneName].filter(candidate => candidate.instanceId !== entry.instanceId),
        ]))
        next.monsterZone.push(summonedEntry)
        return next
      })
      resolvedZone = 'monsterZone'
      setSelectedEntry(summonedEntry)
    }

    setActiveZoneContext(resolvedZone)
    if (summonedEntry) {
      promptOnSummonEffects(card, summonedEntry)
    }
    const apiZone = ['graveyard', 'banished'].includes(zone) ? zone : null
      await fetchComboOptions(card, apiZone, validatedEffect)
    } finally {
      effectResolutionInFlight.current = false
      setEffectInteractionPending(false)
    }
  }

  async function goBackCombo() {
    if (comboHistory.length === 0) return
    const previousState = comboHistory[comboHistory.length - 1]
    setComboHistory(prev => prev.slice(0, -1))
    setComboPath(previousState.comboPath)
    setComboOptions(previousState.comboOptions)
    setZones(previousState.zones)
    setActivatedEffects(previousState.activatedEffects)
    setSummonRestrictions(previousState.summonRestrictions ?? [])
    setActiveZoneContext(previousState.activeZoneContext)
    setSelected(previousState.selected)
    setSelectedEntry(previousState.selectedEntry)
    setActiveEffect(previousState.activeEffect)
    closeEffectPicker()
    setSummonEffectQueue([])
    closeMaterialPicker()
    setExtraDeckPickerOpen(false)
    setPendulumPickerOpen(false)
    await fetchCardExtras(previousState.selected)
  }

  async function handleSearch(e) {
    e.preventDefault()
    if (!query.trim()) return
    setLoading(true)
    setError(null)
    setCards([])

    try {
      if (mode === 'exact') {
        const res = await fetch(`${API}/yugioh/card?name=${encodeURIComponent(query)}`)
        if (!res.ok) throw new Error('Card not found')
        const card = await res.json()
        setCards([card])
        await openRootCard(card)
      } else {
        const res = await fetch(`${API}/yugioh/card/substring?name=${encodeURIComponent(query)}`)
        if (!res.ok) throw new Error('No cards found')
        const list = await res.json()
        setCards(list)
        if (list.length === 1) await openRootCard(list[0])
      }
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  const showList = cards.length > 0

  return (
      <div className="app">
        <header className="app-header">
          <div className="header-title">
            <span className="title-accent">Yu-Gi-Oh!</span> Card Search
          </div>
        </header>

        <main className="app-main">
          <form className="search-form" onSubmit={handleSearch}>
            <div className="search-row">
              <input
                  className="search-input"
                  type="text"
                  placeholder="Enter a card name..."
                  value={query}
                  onChange={e => setQuery(e.target.value)}
              />
              <button className="search-btn" type="submit" disabled={loading}>
                {loading ? '...' : 'Search'}
              </button>
            </div>
            <div className="search-modes">
              <button
                  type="button"
                  className={`mode-pill${mode === 'search' ? ' active' : ''}`}
                  onClick={() => setMode('search')}
              >
                By Name
              </button>
              <button
                  type="button"
                  className={`mode-pill${mode === 'exact' ? ' active' : ''}`}
                  onClick={() => setMode('exact')}
              >
                Exact Match
              </button>
            </div>
          </form>

          {error && <div className="error-msg">{error}</div>}

          {loading && (
              <div className="loading">
                <div className="spinner" />
              </div>
          )}

          {!loading && (
              <div className="results-layout">
                {showList && (
                    <div className="card-list-panel">
                      <div className="panel-label">{cards.length} results</div>
                      {cards.map(c => (
                          <CardListItem
                              key={c.id}
                              card={c}
                              selected={selected?.id === c.id}
                              onClick={() => openRootCard(c)}
                          />
                      ))}
                    </div>
                )}

                <div className="card-detail-panel">
                  {selected ? (
                      <CardDetail
                          card={selected}
                          oncePerTurn={oncePerTurn}
                          extender={extender}
                          comboPath={comboPath}
                          comboOptions={comboOptions}
                          comboLoading={comboLoading}
                          canGoBack={comboHistory.length > 0}
                          onChooseCard={chooseComboOption}
                          onBackCombo={goBackCombo}
                          zones={zones}
                          summonRestrictions={summonRestrictions}
                          activatedEffects={activatedEffects}
                          effectInteractionPending={effectInteractionPending}
                          onRequestEffect={requestEffectActivation}
                          activeEffect={activeEffect}
                          onOpenExtraDeckSummon={() => setExtraDeckPickerOpen(true)}
                          onOpenPendulumSummon={openPendulumSummonPicker}
                          onMoveCard={moveZoneCard}
                          onRemoveCard={removeZoneCard}
                          onClearZones={clearZones}
                          placementPending={placementSelection?.id === selected.id}
                          onPlaceSearchResult={placeSearchResult}
                      />
                  ) : !error && cards.length === 0 && (
                      <div className="empty-state">
                        <div className="empty-state-icon">*</div>
                        <p>Search for a card to begin</p>
                      </div>
                  )}
                </div>
              </div>
          )}
        </main>
        <MaterialPicker
            pendingOption={pendingOption}
            materialPlan={materialPlan}
            loading={materialLoading}
            error={materialError}
            selections={materialSelections}
            search={materialSearch}
            onSearchChange={setMaterialSearch}
            onAddMaterial={addMaterial}
            onRemoveMaterial={removeMaterial}
            onConfirm={confirmMaterialPayment}
            onCancel={closeMaterialPicker}
            zones={zones}
        />
        <EffectPicker
            selection={effectSelection}
            activatedEffects={activatedEffects}
            busy={effectInteractionPending}
            onChoose={chooseEffect}
            onCancel={closeEffectPicker}
        />
        {extraDeckPickerOpen && (
            <ExtraDeckSummonPicker
                zones={zones}
                summonRestrictions={summonRestrictions}
                onSummon={performExtraDeckSummon}
                onCancel={() => setExtraDeckPickerOpen(false)}
            />
        )}
        {pendulumPickerOpen && (
            <PendulumSummonPicker
                zones={zones}
                onSummon={performPendulumSummon}
                onCancel={() => setPendulumPickerOpen(false)}
            />
        )}
      </div>
  )
}
