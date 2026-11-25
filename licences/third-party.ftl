<#-- One line per dependency for scripts/licence_gate.py: groupId:artifactId, then each declared licence, tab-separated. -->
<#list dependencyMap as e>
${e.getKey().groupId}:${e.getKey().artifactId}<#list e.getValue() as licence>	${licence}</#list>
</#list>
