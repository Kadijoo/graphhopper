# Binôme:
Ingabire Kady Danella 20209230
Christelle Phalonne Djuidje 20259975

# Tâche 2

## Classe sélectionnée
Nous avons choisi de travailler sur la classe :

- **'com.graphhopper.routing.weighting.custom.CustomWeighting'**

---

**Score de mutation avec les tests originaux : 58%** 

### Les 7 premiers tests :
1. **T1 – `weightIsSymmetricForBidirectionalEdge`**  
   → Vérifie la symétrie des poids entre aller et retour pour un edge bidirectionnel.

2. **T2 – `weightIncreasesWithDistance`**  
   → S’assure que le poids augmente avec la distance à vitesse constante.

3. **T3 – `higherDistanceInfluenceIncreasesWeight`**  
   → Valide que le paramètre *distance_influence* accroît le poids.

4. **T4 – `priorityMultiplyReducesPriorityIncreasesWeight`**  
   → Vérifie que la réduction de priorité (×0.5) augmente le poids.

5. **T5 – `carAccessFalsePenalizesViaCustomModel`**  
   → Confirme que `car_access = false` pénalise le coût via le CustomModel.

6. **T6 – `zeroDistanceGivesZeroWeight`**  
   → Vérifie que la distance nulle donne un poids nul.

7. **T7 – `fakerBasedMonotonicitySample`**  
   → Utilise **Java Faker** pour générer 20 cas aléatoires reproductibles et tester la monotonie du poids.

**Score de mutation après ces 7 tests : 58%**  
*(aucun nouveau mutant détecté)*

---

### Tests supplémentaires pour tuer de nouveaux mutants

8. **T8 – `minWeightPerDistanceIncreasesWithDI`**  
   - Objectif : détecter les mutants de type **MATH** (addition remplacée par soustraction).  
   - Mutant tué : *Replaced double addition with subtraction → KILLED*.  
   - Vérifie que le terme `distanceInfluence` est bien ajouté (+) et non soustrait.

9. **T9 – `negativeDistanceInfluenceRejected`**  
   - Objectif : détecter les mutants de type **REMOVE_CONDITIONAL** (condition supprimée).  
   - Mutant tué : *removed conditional - replaced comparison check with false → KILLED*.  
   - Vérifie que `distanceInfluence < 0` lève bien une `IllegalArgumentException`.

10. **T10 – `hasTurnCostsCoverage`**  
   - Objectif : détecter les mutants logiques sur les retours booléens.  
   - Mutants tués :  
     - *replaced boolean return with false → KILLED*  
     - *replaced boolean return with true → KILLED*  
     - *removed conditional - replaced equality check with true/false → KILLED*  
   - Vérifie que `hasTurnCosts()` retourne bien `false` sans TurnCostProvider et `true` avec.

**Score de mutation après les tests supplémentaires : 59%**  
*(6 nouveaux mutants détectés)*

