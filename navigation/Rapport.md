# Modification de workflows
## Choix de conception
✔ Baseline enregistrée dans le dépôt

Un fichier ci/mutation-baseline.txt contient le score minimal attendu (81 %).

Ce choix permet de versionner le niveau minimal de qualité des tests et d’identifier toute régression automatiquement.

✔ Exécution de PIT uniquement sur core (Java 24)

PIT est coûteux.
Nous limitons son exécution au module graphhopper-core (coeur logique du projet), à Java 24 : version stable pour PIT. Cela réduit fortement le temps CI tout en restant pertinent.

✔ Extraction automatique du score

Le score est extrait directement depuis : core/target/pit-reports/index.html via un petit script Python intégré dans le workflow. Ensuite, il est comparé à la baseline.

## Choix d' implémentation
-Étape ajoutée dans GitHub Actions

-Exécuter PIT sur le module core :
mvn -B -DskipTests install
mvn -B -pl core -am -Ppitest org.pitest:pitest-maven:mutationCoverage

-Comparer le score courant à la baseline :
si CURRENT < BASELINE → exit 1 → build échoue
sinon → build passe

## Validation
✔ Cas 1 : Build accepté (normal)

baseline = 81

score PIT = 81

Résultat : Pas de régression.

✔ Cas 2 : Build rejeté (test volontaire)

baseline artificiellement montée à 90

score PIT = 81

Résultat : Mutation score decreased from 90 to 81. Failing the build.

Cela prouve que le mécanisme fonctionne.


# Nouveaux cas de tests

## Choix des classes testées.
Nous avons testé les classes DistanceConfig et VoiceInstructionConfig (via sa sous-classe concrète FixedDistanceVoiceInstructionConfig). Ces classes appartiennent au module navigation et sont responsables de la génération des instructions vocales (choix du moment où l’instruction est prononcée et construction du texte). Leur logique conditionnelle est centrale pour l’expérience de navigation et était relativement peu exercée par les tests existants.

## Choix des classes simulées.
Nous avons simulé les classes TranslationMap et Translation, qui fournissent les chaînes traduites utilisées dans les messages vocaux. Ces dépendances accèdent à des ressources externes (fichiers de traduction) et ne font pas partie de la logique métier que nous voulons valider. Les mocker avec Mockito nous permet d’isoler le comportement des classes de navigation sans dépendre des traductions réelles.

## Définition des mocks.
Avec Mockito, nous avons défini les mocks de sorte à contrôler leurs réponses : translationMap.getWithFallBack(locale) renvoie un Translation simulé, et translation.tr(...) renvoie une chaîne fixe comme "Mocked instruction" ou "Mocked text". Cela nous permet de vérifier que les méthodes de traduction sont bien appelées et d’ignorer complètement le contenu réel des fichiers de ressources.

## Choix des valeurs simulées.
Nous avons choisi des distances supérieures aux seuils configurés (par exemple 2500 m pour un seuil à 2000 m) afin de forcer la génération d’instructions vocales et de tester la branche “distance suffisante → instruction produite”. Nous utilisons la locale Locale.ENGLISH par simplicité, et des chaînes simulées constantes pour vérifier que la description retournée intègre la sortie de Translation.tr(...) sans dépendre des traductions concrètes.


# Élément d’humour : Rickroll sur échec de tests.
Pour répondre à la consigne d’introduire un élément d’humour dans la suite de tests, nous avons ajouté une étape conditionnelle à la fin du workflow GitHub Actions. Lorsque le job Build and Test échoue (tests JUnit ou vérification PIT en échec), une action réutilisable publique (darzu/random-rickroll@v1) est invoquée avec la condition if: failure().
Cette action affiche un Rickroll (lien et message humoristique) dans les logs du workflow. Le comportement normal de la CI n’est pas modifié (le build reste en échec), mais les personnes développant sur le projet reçoivent un “Never gonna give you up…” en bonus lorsqu’un test casse.