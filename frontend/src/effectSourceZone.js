function usesThisCardFromHand(text) {
  const lower = text.toLowerCase()
  return /\bdiscard this card\b/.test(lower)
      || /\bthis card (?:is|was) in your hand\b/.test(lower)
      || /\b(?:reveal|send|special summon|normal summon|activate) this card (?:in|from) your hand\b/.test(lower)
      || /\bthis card from your hand\b/.test(lower)
      || /\bwhile this card is in your hand\b/.test(lower)
}

function usesThisCardFromGraveyard(text) {
  const lower = text.toLowerCase()
  return /\b(?:if|when|while) this card (?:is|was) (?:in|sent to) (?:the |your )?(?:gy|graveyard)\b/.test(lower)
      || /\bthis card (?:is|was) in (?:the |your )?(?:gy|graveyard)\b/.test(lower)
      || /\b(?:banish|special summon|set|shuffle|return|add|send) this card from (?:the |your )?(?:gy|graveyard)\b/.test(lower)
      || /\bactivate this card(?:'s effect)? in (?:the |your )?(?:gy|graveyard)\b/.test(lower)
}

function usesThisCardWhileBanished(text) {
  const lower = text.toLowerCase()
  return /\b(?:if|when|while) this card (?:is|was) banished\b/.test(lower)
      || /\bthis banished card\b/.test(lower)
}

export function effectSourceZone(text, card, fallbackZone = null) {
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
  if (usesThisCardFromGraveyard(text)) {
    return 'Graveyard'
  }
  if (usesThisCardWhileBanished(text)) {
    return 'Banished'
  }
  if (fallbackZone) return fallbackZone
  return /(spell|trap)/i.test(card.type || '') ? 'Spell & Trap Zone' : 'Monster Zone'
}
