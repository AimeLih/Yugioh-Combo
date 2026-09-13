function normalizeEffectText(text) {
  return (text || '')
      .replace(/[“”]/g, '"')
      .replace(/’/g, "'")
      .replace(/\r\n/g, '\n')
      .trim()
}

function restrictionClause(sentence) {
  return sentence.match(
      /cannot special summon\s+(.+?)(?=,?\s+(?:the turn|for the rest|during this turn|after this effect)|[.;]|$)/i,
  )?.[1]?.trim() || ''
}

export function activationSummonRestrictions(card, selectedEffect) {
  const selectedSentences = normalizeEffectText(selectedEffect).split(/(?<=[.!?])\s+|\n+/)
  const activationWideSentences = normalizeEffectText(card.description)
      .split(/(?<=[.!?])\s+|\n+/)
      .filter(sentence => /(?:the turn you activate this card|for the rest of this turn|during this turn|after this effect resolves|this turn,\s+you cannot special summon)/i.test(sentence))
  const uniqueSentences = [...new Set([...selectedSentences, ...activationWideSentences])]

  return uniqueSentences
      .filter(sentence => /cannot special summon/i.test(sentence))
      .map(sentence => {
        const clause = restrictionClause(sentence)
        const allowedMatch = clause.match(/\bexcept\s+(.+)$/i)
        return {
          id: `${card.id ?? card.name}:${sentence.toLowerCase()}`,
          sourceCard: card.name,
          text: sentence.trim(),
          scope: /from (?:your |the )?extra deck/i.test(clause) ? 'extraDeck' : 'all',
          allowed: allowedMatch?.[1]?.trim() || '',
          prohibited: allowedMatch ? '' : clause,
        }
      })
}

function descriptorMatchesCard(descriptor, card) {
  const normalized = descriptor.toLowerCase()
  const type = (card.type || '').toLowerCase()
  const name = (card.name || '').toLowerCase()
  const archetype = (card.archetype || '').toLowerCase()
  const attribute = (card.attribute || '').toLowerCase()
  const race = (card.race || '').toLowerCase()
  let recognized = false

  for (const kind of ['fusion', 'synchro', 'xyz', 'link', 'ritual', 'pendulum']) {
    if (new RegExp(`\\b${kind} monsters?\\b`).test(normalized)) {
      recognized = true
      if (type.includes(kind)) return true
    }
  }
  for (const candidateAttribute of ['dark', 'light', 'earth', 'water', 'fire', 'wind', 'divine']) {
    if (new RegExp(`\\b${candidateAttribute} monsters?\\b`).test(normalized)) {
      recognized = true
      if (attribute === candidateAttribute) return true
    }
  }
  const raceMatch = normalized.match(/\b([a-z-]+(?:\s+[a-z-]+)?) monsters?\b/i)?.[1]?.toLowerCase()
  if (raceMatch && !['fusion', 'synchro', 'xyz', 'link', 'ritual', 'pendulum'].includes(raceMatch)) {
    recognized = true
    if (race === raceMatch) return true
  }

  const quotedTerms = [...descriptor.matchAll(/"([^"]+)"/g)]
  for (const match of quotedTerms) {
    recognized = true
    const quoted = match[1].toLowerCase()
    const textAfterQuote = descriptor.slice(match.index + match[0].length)
    const namesFamily = /^\s+(?:monsters?|cards?)\b/i.test(textAfterQuote)
    if (namesFamily ? name.includes(quoted) || archetype === quoted : name === quoted) return true
  }
  if (quotedTerms.length > 0) return false

  const plainDescriptor = normalized
      .replace(/\b(?:from (?:your |the )?extra deck|monsters?|cards?|except|only)\b/g, '')
      .replace(/["']/g, '')
      .trim()
  if (plainDescriptor) {
    recognized = true
    if (name === plainDescriptor || archetype === plainDescriptor) return true
  }
  return recognized ? false : null
}

export function restrictionAllowsCard(restriction, card, fromExtraDeck) {
  if (restriction.scope === 'extraDeck' && !fromExtraDeck) return true
  if (restriction.allowed) return descriptorMatchesCard(restriction.allowed, card) === true
  if (restriction.prohibited) return descriptorMatchesCard(restriction.prohibited, card) === false
  return false
}

export function summonRestrictionReason(restrictions, card, fromExtraDeck, isSpecialSummon = true) {
  if (!isSpecialSummon) return null
  const blocking = restrictions.find(restriction =>
    !restrictionAllowsCard(restriction, card, fromExtraDeck))
  return blocking ? `${blocking.sourceCard}: ${blocking.text}` : null
}
